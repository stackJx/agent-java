"""Local-only OpenAI fixture for browser smoke tests; never calls a real model.

python3 agentcore-interfaces/src/test/js/fake-model.py --port 18989
Messages containing 审批 request a harmless printf tool; 慢速 streams slowly.
模型 echoes the actual requested model ID for dynamic-selection smoke tests.
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class ModelHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.model = request.get("model", "ui-test")
        messages = request.get("messages", [])
        last = messages[-1] if messages else {}
        content = last.get("content", "")
        text = content if isinstance(content, str) else " ".join(
            item.get("text", "") for item in (content or [])
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        try:
            self.chunk({"role": "assistant"})
            self.chunk({"reasoning_content": "这是本地测试模型，不产生 API 费用。"})
            if last.get("role") == "user" and "审批" in text:
                self.chunk({"content": "我会执行一条只输出文字的命令，请确认。"})
                self.chunk({"tool_calls": [{"index": 0, "id": "fixture_call", "type": "function",
                    "function": {"name": "bash", "arguments": json.dumps(
                        {"command": "printf 'AGENTCORE_UI_APPROVED'"})}}]})
                self.chunk({}, "tool_calls")
            else:
                reply = ("## 验证完成\n\n**AgentCore** 已收到消息。\n\n"
                         "- 流式回复正常\n- 历史记录可恢复\n\n"
                         "```java\nSystem.out.println(\"Hello, AgentCore!\");\n```\n")
                if last.get("role") == "tool":
                    reply += "\n工具结果：`" + str(content) + "`\n"
                if "模型" in text:
                    reply = "当前请求模型：`" + self.model + "`\n\n" + reply
                if "慢速" in text:
                    reply = "慢速测试：" + "正在输出，请测试取消或插话。" * 15
                for offset in range(0, len(reply), 8):
                    self.chunk({"content": reply[offset:offset + 8]})
                    time.sleep(0.5 if "慢速" in text else 0.025)
                self.chunk({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # Cancellation intentionally closes the model stream.

    def chunk(self, delta, finish_reason=None):
        value = {"id": "fixture", "object": "chat.completion.chunk", "model": self.model,
                 "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}]}
        if finish_reason:
            value["usage"] = {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200}
        self.wfile.write(("data: " + json.dumps(value) + "\n\n").encode())
        self.wfile.flush()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18989)
    args = parser.parse_args()
    print(f"Local model fixture: http://127.0.0.1:{args.port}/v1", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), ModelHandler).serve_forever()
