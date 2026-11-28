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


def test_model_timeout_downgrades_without_forwarding_contact(monkeypatch):
    received = []

    def timeout(message, filters, metrics, *, timeout):
        received.append((message, filters))
        raise discovery.httpx.ReadTimeout("routing fixture")

    def provider(path, params):
        assert params == {"q": "desk lamp"}
        return {"sources": [{"provider": "shopify", "status": "empty", "offers": []}]}

    monkeypatch.setattr(discovery, "choose", timeout)
    monkeypatch.setattr(discovery, "get", provider)
    graph = discovery.graph_for(InMemorySaver())
    result = discovery.turn(graph, "guest", "帮我找台灯，手机号：13800000000，test@example.invalid")
    assert len(received) == 1
    assert "台灯" in received[0][0]
    assert "13800000000" not in received[0][0] and "@" not in received[0][0]
    assert result["routing"] == "offline"
    assert result["status"] == "degraded"
    assert result["degradation"] == "model_timeout"
    assert result["sources"] == {"shopify": "empty"}


def test_batched_comparison_preserves_displayed_order_across_turns(monkeypatch):
    monkeypatch.setattr(config, "MODE", "offline")
    cards = [{"provider": "shopify", "variantId": str(i), "available": True} for i in range(1, 5)]
    calls = []

    def provider(path, params):
        if not path:
            return {"sources": [{"provider": "shopify", "status": "ok", "offers": cards}]}
        calls.append((path, params["id"]))
        return {"offers": [card for card in cards if card["variantId"] in params["id"]]}

    monkeypatch.setattr(discovery, "get", provider)
    graph = discovery.graph_for(InMemorySaver())
    discovery.turn(graph, "guest", "找能寄到杭州的台灯，预算300元")
    result = discovery.turn(graph, "guest", "比较第4和第2款")
    assert result["comparison"]
    assert [offer["variantId"] for offer in result["offers"]] == ["4", "2"]
    assert result["filters"]["budget"] == 300
    discovery.turn(graph, "guest", "比较第1和第2款")
    assert calls == [("/shopify/compare", ["4", "2"]), ("/shopify/compare", ["4", "2"])]
