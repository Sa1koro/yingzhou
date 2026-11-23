"""Regenerate the fixtures with NDK r23b, SDK build-tools 34.0.0 and a JDK on PATH.

    python rebuild.py /path/to/ndk/toolchains/llvm/prebuilt/<host>/bin/clang /path/to/Android/SDK
"""

import base64
from pathlib import Path
import subprocess
import sys
import tempfile


def main(clang, sdk):
    root = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory() as temporary:
        for suffix, target in (("arm", "armv7a-linux-androideabi21"), ("arm64", "aarch64-linux-android21")):
            for name, links in (("math", ["-nostdlib"]), ("ndk", ["-Wl,--no-as-needed", "-llog", "-landroid", "-lc", "-lm", "-ldl"])):
                library = Path(temporary) / f"lib{name}.so"
                subprocess.run([
                    clang, "-target", target, "-O2", "-fPIC", "-shared", "-fno-ident",
                    "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-Wl,--hash-style=sysv",
                    "-Wl,--build-id=none", f"-Wl,-soname,lib{name}.so", "-Wl,-z,max-page-size=4096", "-s",
                    f"{name}.c", *links, "-o", str(library),
                ], cwd=root, check=True)
                (root / f"lib{name}-{suffix}.so.base64").write_bytes(base64.b64encode(library.read_bytes()) + b"\n")
        platform = Path(sdk).resolve() / 'platforms/android-34/android.jar'
        d8 = Path(sdk).resolve() / 'build-tools/34.0.0/lib/d8.jar'
        subprocess.run(['javac', '--release', '11', '-g:none', '-cp', platform, '-d', temporary, root / 'NativeMath.java'], check=True)
        subprocess.run(['java', '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '21', '--lib', platform,
                        '--output', temporary, Path(temporary) / 'tvbox/fixture/NativeMath.class'], check=True)
        (root / 'math.dex.base64').write_bytes(base64.b64encode((Path(temporary) / 'classes.dex').read_bytes()) + b'\n')


if __name__ == "__main__":
    main(*sys.argv[1:])
