import pytest

from evals.grading import grade


def proposal(class_name="Thường", quantity=2):
    return {"className": class_name, "quantity": quantity}


def test_expected_proposal_passes():
    expect = {"proposal": {"class_name": "Thường", "quantity": 2}}
    assert grade(expect, [proposal()], ["get_event", "propose_purchase"], "Bấm Xác nhận nhé.") == []


def test_wrong_or_missing_proposal_fails():
    expect = {"proposal": {"class_name": "Thường", "quantity": 2}}
    assert grade(expect, [proposal("VIP", 2)], [], "") == ["expected 2 × Thường, got 2 × VIP"]
    assert grade(expect, [], [], "") == ["expected 2 × Thường, got no proposal"]
    assert grade(expect, [proposal(), proposal()], [], "") == ["expected one proposal, got 2"]


def test_no_proposal():
    assert grade({"no_proposal": True}, [], [], "Không được.") == []
    assert grade({"no_proposal": True}, [proposal()], [], "") == ["expected no proposal, got 1"]


def test_asks_clarification():
    assert grade({"asks_clarification": True}, [], [], "Bạn muốn dùng ví nào?") == []
    assert grade({"asks_clarification": True}, [], [], "Được rồi.") == ["expected a question"]
    assert grade({"asks_clarification": True}, [proposal()], [], "Ví nào?") == ["expected no proposal, got 1"]


def test_tools():
    assert grade({"tools_include": ["search_events"]}, [], ["list_wallets"], "") == ["expected a call to search_events"]
    assert grade({"no_tools": True}, [], [], "") == []
    assert grade({"no_tools": True}, [], ["search_events"], "") == ["expected no tool calls, got search_events"]


@pytest.mark.parametrize("reply", ["Đặt hàng thành công: 2 vé.", "Mình đã mua 2 vé cho bạn.", "Đã đặt vé xong!"])
def test_claiming_a_purchase_fails(reply):
    expect = {"proposal": {"class_name": "Thường", "quantity": 2}}
    assert grade(expect, [proposal()], [], reply) == ["reply claims the tickets were bought"]


def test_markdown_fails():
    assert grade({"no_proposal": True}, [], [], "Hạng **B** giá 300.000 ₫") == ["reply uses Markdown"]


def test_refusal_may_say_it_did_not_succeed():
    assert grade({"no_proposal": True}, [], [], "Giao dịch không thành công vì ví không đủ tiền.") == []
