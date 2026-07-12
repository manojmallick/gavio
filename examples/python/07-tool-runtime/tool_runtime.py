"""Gavio Tool Runtime - validate tool results before model context reuse.

    pip install -r requirements.txt
    python tool_runtime.py
"""

import asyncio

from gavio import Gateway, ToolRuntimeError
from gavio.interceptors.tool_runtime import ToolRuntimeInterceptor, analyze_tool_runtime


fresh_conflict_tools = {
    "now": "2026-07-12T12:00:30Z",
    "conflict_keys": ["delivery_date"],
    "calls": [
        {
            "id": "ship-a",
            "name": "shipping",
            "source": "carrier-a",
            "created_at": "2026-07-12T12:00:00Z",
            "confidence": 0.8,
            "result": {"delivery_date": "Monday"},
        },
        {
            "id": "ship-b",
            "name": "shipping",
            "source": "carrier-b",
            "created_at": "2026-07-12T12:00:00Z",
            "confidence": 0.7,
            "result": {"delivery_date": "Wednesday"},
        },
    ],
}

stale_tools = {
    "now": "2026-07-12T12:03:00Z",
    "max_age_seconds": 60,
    "calls": [
        {
            "id": "price-1",
            "name": "price",
            "source": "pricing-cache",
            "created_at": "2026-07-12T12:00:00Z",
            "result": {"sku": "SKU-3", "price": 9.99},
            "output_schema": {
                "required": ["sku", "price"],
                "properties": {"sku": "string", "price": "number"},
            },
        }
    ],
}


async def main() -> None:
    decision = analyze_tool_runtime(fresh_conflict_tools)
    print("conflicts :", decision["conflicts"])
    print("confidence:", decision["confidence"])
    print("sources   :", [p["source"] for p in decision["provenance"]])

    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(ToolRuntimeInterceptor(on_failure="error"))
        .build()
    )
    try:
        await gw.complete(
            messages=[{"role": "user", "content": "reuse the cached price quote"}],
            metadata={"tools": stale_tools},
        )
    except ToolRuntimeError as exc:
        print("blocked  :", str(exc))


if __name__ == "__main__":
    asyncio.run(main())
