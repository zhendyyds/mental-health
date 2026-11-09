"""Create one dense Cosine collection on the private Compose Qdrant service."""

import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    name = os.environ["RAG_COLLECTION"]
    size = int(os.environ["RAG_EMBEDDING_DIMENSION"])
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,63}", name) or not 1 <= size <= 8192:
        raise ValueError("Invalid collection name or vector dimension")

    url = f"http://qdrant:6333/collections/{name}"
    desired = {"size": size, "distance": "Cosine"}
    try:
        with urlopen(url, timeout=5) as response:
            collection = json.load(response)
    except HTTPError as missing:
        if missing.code != 404:
            raise
        payload = json.dumps({"vectors": desired}).encode()
        create = Request(url, data=payload, headers={"Content-Type": "application/json"}, method="PUT")
        try:
            with urlopen(create, timeout=10) as response:
                if json.load(response).get("result") is not True:
                    raise ValueError("Qdrant did not acknowledge the collection creation")
        except HTTPError as conflict:
            if conflict.code not in (400, 409):
                raise
        with urlopen(url, timeout=5) as response:
            collection = json.load(response)

    vectors = collection["result"]["config"]["params"]["vectors"]
    if not isinstance(vectors, dict) or any(vectors.get(key) != value for key, value in desired.items()):
        raise ValueError("Existing collection does not have the configured dense Cosine shape")
    print(f"Collection {name} ready, dense Cosine, dimension {size}")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, URLError) as error:
        print(f"Collection preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
