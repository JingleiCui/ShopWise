"""Versioned Chinese discovery acceptance matrix using deterministic catalog fixtures."""
import argparse
import itertools
import json
from collections import Counter
from pathlib import Path
from unittest.mock import patch

import httpx
from langgraph.checkpoint.memory import InMemorySaver

import config
import discovery

PRODUCTS = {
    "阅读灯": "desk lamp", "背包": "backpack", "耳机": "headphones",
    "充电器": "charger", "手机壳": "phone case",
}
PROMPTS = [
    "能寄到杭州，预算300元，帮我找{product}", "我想买{product}，寄到杭州，最多300元",
    "推荐一款{product}，送到杭州，预算300块", "给杭州选购{product}，配送至杭州，不超过300元",
    "看看{product}，寄到杭州市，控制在300元以内",
]
FOLLOWUPS = {
    "budget": "把预算改成500", "reset": "预算不限", "stock": "只看现货",
    "compare": "比较第2和第4款", "source_partial": "再找一次",
    "catalog_unavailable": "重新查询", "private": "改成500，联系 safe-fixture@example.invalid",
    "read_only": "把第1个加入购物车",
}
OFFERS = [
    {"provider": "shopify", "variantId": f"fixture-{index}", "available": index % 2 == 1}
    for index in range(1, 5)
]


def evaluate(mode):
    config.MODE = mode
    failures = []
    tokens = Counter()
    count = tool_passes = critical = 0
    for (product, keyword), prompt, (case, question) in itertools.product(
        PRODUCTS.items(), PROMPTS, FOLLOWUPS.items()
    ):
        count += 1
        graph = discovery.graph_for(InMemorySaver())
        calls = []
        problem = None

        def catalog(path, params):
            calls.append((path, params))
            if problem == "catalog_unavailable":
                raise httpx.ReadTimeout("catalog fixture")
            if path:
                return {"offers": [card for card in OFFERS if card["variantId"] in params["id"]]}
            sources = [{"provider": "shopify", "status": "ok", "offers": OFFERS}]
            if problem == "source_partial":
                sources.append({"provider": "ebay", "status": "timeout", "offers": []})
            return {"sources": sources}

        with patch.object(discovery, "get", catalog):
            first = discovery.turn(graph, "scenario", prompt.format(product=product))
            first_call = calls[:]
            calls.clear()
            problem = case
            second = discovery.turn(graph, "scenario", question)
        filters = second["filters"]
        ids = [c["variantId"] for c in second["offers"]]
        initial_tool = len(first_call) == 1 and first_call[0][0] == "" and (
            first_call[0][1].get("q", "").startswith(keyword) and first_call[0][1].get("budget") == 300
        )
        if case == "compare":
            followup_tool = calls == [("/shopify/compare", {"id": ["fixture-2", "fixture-4"]})]
        elif case == "read_only":
            followup_tool = not calls
        else:
            budget = None if case == "reset" else 500 if case in {"budget", "private"} else 300
            followup_tool = len(calls) == 1 and calls[0][0] == "" and (
                calls[0][1].get("q", "").startswith(keyword) and calls[0][1].get("budget") == budget
            )
        routed = first["routing"] == mode and second["routing"] == mode
        tool_ok = initial_tool and followup_tool and routed
        tool_passes += tool_ok
        checks = {
            "initial": first["filters"].get("city") == "杭州" and first["filters"].get("budget") == 300,
            "tool": tool_ok,
            "context": filters.get("city") == "杭州" and filters.get("keyword") == keyword,
            "evidence": second["evidence"] == {"shipping": "unknown", "tax": "unknown"},
            "case": {
                "budget": filters.get("budget") == 500 and calls != [] and calls[0][1].get("budget") == 500,
                "reset": "budget" not in filters and calls != [] and "budget" not in calls[0][1],
                "stock": ids == ["fixture-1", "fixture-3"],
                "compare": ids == ["fixture-2", "fixture-4"] and second["comparison"],
                "source_partial": second["degradation"] == case and len(ids) == 4,
                "catalog_unavailable": second["degradation"] == case and not ids,
                "private": filters.get("budget") == 500 and "@" not in repr(calls) and ".invalid" not in repr(calls),
                "read_only": not calls and not ids,
            }[case],
        }
        if not checks["evidence"] or (case in {"read_only", "private"} and not checks["case"]):
            critical += 1
        if not all(checks.values()):
            failures.append({"id": count, "product": product, "case": case,
                             "failed": [key for key, value in checks.items() if not value]})
        for turn in (first, second):
            tokens[turn["routing"]] += turn["tokens"]
    success_rate = (count - len(failures)) / count
    tool_accuracy = tool_passes / count
    return {"dataset": "zh-discovery-fixtures-v1", "mode": mode, "scenarios": count,
            "passed": count - len(failures), "successRate": success_rate, "toolAccuracy": tool_accuracy,
            "criticalFailures": critical, "gatePassed": success_rate >= .90 and tool_accuracy >= .95 and not critical,
            "tokens": dict(tokens), "failures": failures}


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--live", action="store_true", help="Evaluate the configured model against fake catalog data")
    cli.add_argument("--report", type=Path)
    args = cli.parse_args()
    if args.live and not config.API_KEY:
        cli.error("--live requires a configured model API key")
    report = evaluate("live" if args.live else "offline")
    output = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output + "\n", encoding="utf-8")
    print(output)
    raise SystemExit(not report["gatePassed"] or (not args.live and bool(report["failures"])))
