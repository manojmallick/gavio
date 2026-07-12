from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "_shared"))

from recipe_smoke import main

if __name__ == "__main__":
    main("openlit")
