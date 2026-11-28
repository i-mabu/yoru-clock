#!/usr/bin/env python3
"""Compose 依存ライブラリの最小 Maven 解決ツール。

ルート成果物 (Compose UI / Material3 / activity-compose) の POM を辿って
コンパイル・実行時依存を集め、aar / jar を libs/ へダウンロードする。
参照先は Google Maven (androidx) → Maven Central の順。

ダウンロードできたファイル名を libs/artifacts.txt に 1 行 1 件で書き出す
(build-apk.sh がこの一覧の順序で読み込む)。1 件でも取得できない場合は
artifacts.txt を書かず (古いものが残っていれば消し)、非ゼロで終了する。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS_DIR = os.path.join(PROJECT_ROOT, "libs")
POM_CACHE = os.path.join(PROJECT_ROOT, "build", "pomcache")
os.makedirs(LIBS_DIR, exist_ok=True)
os.makedirs(POM_CACHE, exist_ok=True)

REPOSITORIES = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# 解決の起点 (groupId, artifactId, version)
ROOT_ARTIFACTS = [
    ("androidx.compose.ui", "ui", "1.7.6"),
    ("androidx.compose.ui", "ui-graphics", "1.7.6"),
    ("androidx.compose.ui", "ui-text", "1.7.6"),
    ("androidx.compose.ui", "ui-unit", "1.7.6"),
    ("androidx.compose.foundation", "foundation", "1.7.6"),
    ("androidx.compose.foundation", "foundation-layout", "1.7.6"),
    ("androidx.compose.runtime", "runtime", "1.7.6"),
    ("androidx.compose.runtime", "runtime-saveable", "1.7.6"),
    ("androidx.compose.material3", "material3", "1.3.1"),
    ("androidx.activity", "activity-compose", "1.9.3"),
]

# 除外する成果物: collection-ktx のクラスは collection-jvm に統合済みで、
# 両方を dex に入れると duplicate class になるため片方を落とす。
EXCLUDED = {
    ("androidx.collection", "collection-ktx"),
}

# ファミリー内で版を揃えるための固定 (Gradle の最版選択に相当)
VERSION_PINS = {
    ("androidx.lifecycle", "lifecycle-common"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-common-java8"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime-ktx"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel-savedstate"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-process"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime-compose"): "2.8.7",
    ("androidx.core", "core"): "1.13.1",
    ("androidx.core", "core-ktx"): "1.13.1",
    ("androidx.arch.core", "core-common"): "2.2.0",
    ("androidx.arch.core", "core-runtime"): "2.2.0",
}


def repo_path(group, artifact, version, filename):
    return "{}/{}/{}/{}".format(group.replace(".", "/"), artifact, version, filename)


def download(url, dest=None):
    """curl で取得する。dest があればファイルへ保存し、そのパスを返す。"""
    if dest and os.path.exists(dest):
        return dest
    result = subprocess.run(["curl", "-sfL", url], capture_output=True)
    if result.returncode != 0:
        return None
    if dest:
        with open(dest, "wb") as fh:
            fh.write(result.stdout)
        return dest
    return result.stdout


def fetch_pom(group, artifact, version):
    dest = os.path.join(POM_CACHE, "{}_{}_{}.pom".format(group, artifact, version))
    if os.path.exists(dest):
        return dest
    filename = "{}-{}.pom".format(artifact, version)
    for repo in REPOSITORIES:
        if download("{}/{}".format(repo, repo_path(group, artifact, version, filename)), dest):
            return dest
    return None


def child_text(element, tag):
    node = element.find("m:" + tag, NS)
    if node is None or node.text is None:
        return None
    text = node.text.strip()
    return text if text else None


def interpolate(value, properties):
    """${...} プレースホルダを POM のプロパティで置換する (数回繰り返して解決)。"""
    if value is None:
        return None
    for _ in range(5):
        if "${" not in value:
            break
        for key, replacement in properties.items():
            value = value.replace("${%s}" % key, replacement)
    return value


def normalize_version(version):
    if version is None:
        return None
    version = version.strip()
    # 版範囲 "[1.0,2.0)" のような記法は下限側を採る
    if version.startswith("[") and version.endswith("]"):
        version = version[1:-1].split(",")[0].strip()
    return version


def parse_pom(group, artifact, version, depth=0):
    """(properties, dependencies, packaging) を返す。dependencies は
    (group, artifact, version, type, scope, optional) のタプル列。"""
    pom_file = fetch_pom(group, artifact, version)
    if not pom_file:
        print("  !! POM not found: {}:{}:{}".format(group, artifact, version))
        return {}, [], "jar"
    root = ET.parse(pom_file).getroot()

    properties = {}
    props_node = root.find("m:properties", NS)
    if props_node is not None:
        for child in props_node:
            properties[child.tag.split("}")[-1]] = (child.text or "").strip()
    own_version = child_text(root, "version") or version
    properties["project.version"] = own_version
    properties["pom.version"] = own_version

    # 親 POM のプロパティを取り込む (依存の版管理は自 POM 側を優先)
    parent = root.find("m:parent", NS)
    if parent is not None and depth < 4:
        parent_coords = (
            child_text(parent, "groupId"),
            child_text(parent, "artifactId"),
            child_text(parent, "version"),
        )
        if all(parent_coords):
            parent_props, _, _ = parse_pom(*parent_coords, depth + 1)
            for key, val in parent_props.items():
                properties.setdefault(key, val)

    managed = {}
    managed_node = root.find("m:dependencyManagement/m:dependencies", NS)
    if managed_node is not None:
        for dep in managed_node.findall("m:dependency", NS):
            dg, da = child_text(dep, "groupId"), child_text(dep, "artifactId")
            dv = child_text(dep, "version")
            if dg and da and dv:
                managed[(dg, da)] = interpolate(dv, properties)

    dependencies = []
    deps_node = root.find("m:dependencies", NS)
    if deps_node is not None:
        for dep in deps_node.findall("m:dependency", NS):
            dg, da = child_text(dep, "groupId"), child_text(dep, "artifactId")
            dv = child_text(dep, "version")
            if dv is None:
                dv = managed.get((dg, da))
            dv = normalize_version(interpolate(dv, properties))
            if (dg, da) in VERSION_PINS:
                dv = VERSION_PINS[(dg, da)]
            scope = child_text(dep, "scope") or "compile"
            dep_type = child_text(dep, "type") or "jar"
            optional = (child_text(dep, "optional") or "false") == "true"
            if dg and da and dv:
                dependencies.append((dg, da, dv, dep_type, scope, optional))
    packaging = child_text(root, "packaging") or "jar"
    return properties, dependencies, packaging


def download_artifact(group, artifact, version, packaging):
    extension = "aar" if packaging == "aar" else "jar"
    filename = "{}-{}.{}".format(artifact, version, extension)
    dest = os.path.join(LIBS_DIR, filename)
    if os.path.exists(dest):
        return dest
    for repo in REPOSITORIES:
        url = "{}/{}".format(repo, repo_path(group, artifact, version, filename))
        if download(url, dest):
            return dest
    return None


def main():
    chosen = {}   # (group, artifact) -> version
    resolved = []  # (group, artifact, version, packaging)
    queue = deque(ROOT_ARTIFACTS)
    for group, artifact, version in ROOT_ARTIFACTS:
        chosen[(group, artifact)] = version

    while queue:
        group, artifact, version = queue.popleft()
        _, dependencies, packaging = parse_pom(group, artifact, version)
        resolved.append((group, artifact, version, packaging))
        print("{}:{}:{} [{}] deps={}".format(group, artifact, version, packaging, len(dependencies)))
        for dg, da, dv, _type, scope, optional in dependencies:
            if optional or scope in ("test", "provided", "system", "import"):
                continue
            if (dg, da) in EXCLUDED or (dg, da) in chosen:
                continue
            chosen[(dg, da)] = dv
            queue.append((dg, da, dv))

    print("\n=== downloading artifacts ===")
    succeeded = []
    missing = []
    for group, artifact, version, packaging in resolved:
        path = download_artifact(group, artifact, version, packaging)
        if path is None:
            # packaging の申告と実体が違う場合に備え、もう一方の拡張子も試す
            alternate = "jar" if packaging == "aar" else "aar"
            path = download_artifact(group, artifact, version, alternate)
        if path:
            succeeded.append((group, artifact, version, os.path.basename(path)))
        else:
            missing.append("{}:{}:{} ({})".format(group, artifact, version, packaging))

    print("\nOK {}:".format(len(succeeded)))
    for group, artifact, version, filename in succeeded:
        print("  {}:{}:{} -> {}".format(group, artifact, version, filename))
    print("\nMISSING {}:".format(len(missing)))
    for entry in missing:
        print("  " + entry)

    manifest = os.path.join(LIBS_DIR, "artifacts.txt")
    if missing:
        if os.path.exists(manifest):
            os.remove(manifest)
        print(
            "error: {} artifact(s) could not be downloaded".format(len(missing)),
            file=sys.stderr,
        )
        return 1
    with open(manifest, "w") as fh:
        for _, _, _, filename in succeeded:
            fh.write(filename + "\n")
    print("wrote libs/artifacts.txt ({} entries)".format(len(succeeded)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
