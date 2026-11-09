"""Accept only directories and regular files within an uploads tarball."""

import sys
import tarfile
from pathlib import PurePosixPath


def validate_archive(path):
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            name = member.name
            entry = PurePosixPath(name)
            if (entry.is_absolute() or ".." in entry.parts or "\\" in name
                    or any(ord(char) < 32 or ord(char) == 127 for char in name)
                    or not (member.isdir() or member.isfile())):
                raise ValueError("Unsafe upload archive entry")


if __name__ == "__main__":
    try:
        validate_archive(sys.argv[1])
    except (OSError, tarfile.TarError, ValueError):
        sys.exit("Upload archive is invalid or contains unsafe entries")
