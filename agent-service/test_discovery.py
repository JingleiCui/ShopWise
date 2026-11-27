from langgraph.checkpoint.memory import InMemorySaver

import discovery
import config


def test_china_budget_and_transient_catalog(monkeypatch):
    monkeypatch.setattr(config, "MODE", "offline")
    calls = []
    card = {"provider": "shopify", "variantId": "fixture-only", "available": True,
            "url": "https://example.com/not-in-checkpoint"}
    def provider(path, params):
        calls.append(params)
        return {"sources": [{"provider": "shopify", "status": "ok", "offers": [card]}]}
    monkeypatch.setattr(discovery, "get", provider)
    graph = discovery.graph_for(InMemorySaver())
    initial = discovery.turn(graph, "guest", "帮我找一盏能寄到杭州、到手不超过 300 元的阅读灯")
    followup = discovery.turn(graph, "guest", "把预算改成 500")
    assert initial["offers"] == [card]
    assert initial["filters"]["landed"] is True
    assert followup["filters"]["city"] == "杭州"
    assert calls == [{"q": "desk lamp reading", "budget": 300}, {"q": "desk lamp reading", "budget": 500}]
    state = graph.get_state({"configurable": {"thread_id": "discovery/guest"}}).values
    assert card["url"] not in repr(state)
    assert "税费" in initial["text"]
