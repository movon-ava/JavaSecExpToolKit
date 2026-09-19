#!/usr/bin/env python3
"""Fastjson 识别 / 版本 / 期望类 / DNS 探针引擎（仅识别，不做利用）。

本引擎只发送无害的畸形 JSON 与 @type 探针，观察响应差异来推断：

1. 目标是否为 Fastjson（与 Jackson / Gson / org.json / Hutool 对比）
2. Fastjson 版本区间（回显精确版本 + 不出网布尔探针 + DNS 探针）
3. 反序列化点是否存在「期望类」（绑定具体 Java 类型）
4. DNS 探针是否收到解析请求，并可选通过 CEYE 接口确认记录

引擎不生成利用链，不执行命令、不读写文件、不做内存马。
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import shlex
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional, Sequence, Tuple

DEFAULT_TIMEOUT = 8.0

# 探针并发度。
#
# 每轮探针都是「同一个目标、不同 payload」的独立请求，串行发完 8~11 条会让探测
# 时间随探针数线性增长（黑洞目标下 detect 约 20 秒、version 约 27 秒）。
# 并发上限刻意压低到 4：探针总数不多，加速已经足够，又不至于把目标的日志
# 冲得看不清，也不会因为瞬时连接数过多触发 WAF 的频率限制。
PROBE_CONCURRENCY = 4
DEFAULT_CONTENT_TYPE = "application/json"
DEFAULT_DNS_WAIT = 13.0
DEFAULT_CEYE_API = "http://api.ceye.io/v1/records"
DEFAULT_CEYE_DOMAIN = ""

MODE_LABELS = {
    "detect": "Fastjson 识别",
    "version": "版本识别",
    "expect": "期望类",
    "dns": "DNS 探针",
    "ceye": "CEYE 确认",
    "capture": "HTTP 抓包",
    "convert": "报文转换",
}


@dataclass
class ProbeResult:
    probe_id: str
    category: str
    description: str
    status: Optional[int]
    elapsed_ms: Optional[float]
    errored: Optional[bool]
    matched: List[str]
    response_excerpt: str
    error: Optional[str] = None


SUPPORTED_REQUEST_METHODS = ("POST", "GET", "PUT", "PATCH", "DELETE", "OPTIONS")

# 登录页特征：目标存在登录拦截时，未登录的请求会被转发到登录视图。
#
# 实测靶场（Hello-Java-Sec）的 LoginHandlerInterceptor 对未登录请求 forward 到 /login：
# POST 落到只支持 GET 的登录视图 → 405 空响应（Allow: GET, HEAD）；
# GET 则返回同一份登录页 HTML。只看 405 会得出「该路径只接受 GET」的错误结论，
# 因此按「多特征同时命中」而不是单一关键词识别登录页，避免把普通页面误判成登录页。
LOGIN_PAGE_MARKERS: Tuple[str, ...] = (
    "请先登录",
    "用户登录",
    "name=\"password\"",
    "name='password'",
    "/user/login",
    "/captcha",
    "记住我",
    "remember me",
)

LOGIN_GATE_HINT = (
    "目标存在登录拦截：未登录的请求被转发到登录页。"
    "请先登录并把会话 Cookie 作为请求头传入（配置页「会话 Cookie」，"
    "或先在「代理抓包」页登录，再用「一键发送」把带 Cookie 的请求送到探测页）。"
)


def _request_method(extras: Dict[str, Any]) -> str:
    """解析探测使用的请求方法；缺省 POST，非法值回落到 POST。"""
    value = str(extras.get("probe_method") or extras.get("method") or "POST").strip().upper()
    return value if value in SUPPORTED_REQUEST_METHODS else "POST"


def _excerpt(value: str, limit: int = 500) -> str:
    value = (value or "").replace("\r", "\\r").replace("\n", "\\n")
    return value if len(value) <= limit else value[: limit - 3] + "..."


def _markers(text: str, candidates: Sequence[str]) -> List[str]:
    lowered = (text or "").lower()
    return [marker for marker in candidates if marker.lower() in lowered]


def _normalize_target(target: str) -> str:
    target = (target or "").strip()
    if not target:
        raise ValueError("target 不能为空")
    if not target.startswith(("http://", "https://")):
        target = "http://" + target
    return target


def _dns_safe_label(value: str, limit: int = 20) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9]+", "", value or "")
    return (cleaned or "fj")[:limit].lower()


def _strip_dns_host(value: str) -> str:
    host = (value or "").strip()
    if "://" in host:
        host = host.split("://", 1)[1]
    host = host.split("/", 1)[0]
    return host.strip().rstrip(".")


CONFIG_FILE = os.path.join(os.path.expanduser("~"), ".JavaSecExpToolKit", "config.properties")
_config_cache: Optional[Dict[str, str]] = None

_CONFIG_ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    "f": "\f",
    "\\": "\\",
    ":": ":",
    "=": "=",
    " ": " ",
}


def _unescape_config(value: str) -> str:
    """还原 Java Properties.store 的转义（``\\:``、``\\\\``、``\\uXXXX`` 等）。"""
    out: List[str] = []
    index = 0
    while index < len(value):
        char = value[index]
        if char != "\\" or index + 1 >= len(value):
            out.append(char)
            index += 1
            continue
        nxt = value[index + 1]
        if nxt == "u" and index + 5 < len(value):
            try:
                out.append(chr(int(value[index + 2 : index + 6], 16)))
                index += 6
                continue
            except ValueError:
                pass
        out.append(_CONFIG_ESCAPES.get(nxt, nxt))
        index += 2
    return "".join(out)


def _config_file() -> Dict[str, str]:
    """读取桌面端配置页保存的 config.properties（缺失时返回空字典）。"""
    global _config_cache
    if _config_cache is not None:
        return _config_cache
    values: Dict[str, str] = {}
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as handle:
            lines = _join_continuations(handle.read().splitlines())
    except OSError:
        lines = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[_unescape_config(key.strip())] = _unescape_config(value.strip())
    _config_cache = values
    return values


def _join_continuations(raw_lines: List[str]) -> List[str]:
    """合并 Java Properties 长值折行（行尾单个反斜杠表示续行）。"""
    joined: List[str] = []
    pending = ""
    for raw in raw_lines:
        line = pending + raw
        pending = ""
        trailing = len(line) - len(line.rstrip("\\"))
        if trailing % 2 == 1:
            pending = line[:-1]
            continue
        joined.append(line)
    if pending:
        joined.append(pending)
    return joined


def _request(
    target: str,
    payload: str,
    timeout: float,
    headers: Dict[str, str],
    content_type: str = DEFAULT_CONTENT_TYPE,
    method: str = "POST",
) -> Tuple[Optional[int], Optional[float], str, Optional[str]]:
    """发送一条探测请求。

    `method` 默认 POST（JSON 反序列化接口的常见形态）；
    GET / HEAD 没有请求体，改把 payload 转义后放进查询串，
    用于判断“只接收 GET 的接口”；其余方法按原样发送请求体。
    """
    verb = (method or "POST").strip().upper() or "POST"
    url = target
    data = payload.encode("utf-8")
    if verb in ("GET", "HEAD"):
        # 无实体方法把 payload 放进查询串；必须 URL 编码，否则 JSON 里的空格 / 引号
        # 会被 http.client 判定为非法请求行（InvalidURL）。
        data = None
        if payload:
            encoded = urllib.parse.quote(payload, safe="")
            url = target + ("&" if "?" in target else "?") + encoded
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": content_type, **headers},
        method=verb,
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(1024 * 1024).decode("utf-8", errors="replace")
            status = response.getcode()
    except urllib.error.HTTPError as exc:
        body = exc.read(1024 * 1024).decode("utf-8", errors="replace")
        status = exc.code
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        elapsed = (time.perf_counter() - started) * 1000
        return None, round(elapsed, 2), "", "{0}: {1}".format(type(exc).__name__, exc)
    elapsed = (time.perf_counter() - started) * 1000
    return status, round(elapsed, 2), body, None


def _send_probes(
    probes: Sequence[Any],
    target: str,
    timeout: float,
    headers: Dict[str, str],
    content_type: str,
    method: str,
) -> List[Tuple[Optional[int], Optional[float], str, Optional[str]]]:
    """并发发送一批探针，返回顺序与 probes 完全一致。

    探针之间互不依赖（各自是独立的一次请求），串行发只是历史实现留下的顺序，
    却让探测时间随探针数线性增长。这里用固定并发度发送并按原下标回填结果，
    保证上层拿到的 evidence 顺序、以及所有依赖「第 N 条探针」的判定逻辑都不变。

    单条探针抛出的异常会被收敛成「连接失败」，避免线程池把异常带成整体失败。
    """
    results: List[Optional[Tuple[Optional[int], Optional[float], str, Optional[str]]]] = [
        None
    ] * len(probes)
    if not probes:
        return []

    def send_one(index: int) -> None:
        try:
            # 探针表既有 (id, description, payload) 也有 (id, category, description, payload)，
            # payload 恒为最后一个元素，按 -1 取才不会因元组长短不同而越界
            results[index] = _request(
                target, probes[index][-1], timeout, headers, content_type, method
            )
        except Exception as exc:  # noqa: BLE001
            results[index] = (None, 0.0, "", "{0}: {1}".format(type(exc).__name__, exc))

    workers = max(1, min(PROBE_CONCURRENCY, len(probes)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for index in range(len(probes)):
            pool.submit(send_one, index)

    return [item if item is not None else (None, 0.0, "", "探针未执行") for item in results]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """抓包场景保留 30x 原始响应，不自动跟随跳转。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _request_raw(
    method: str,
    target: str,
    body: str,
    timeout: float,
    headers: Dict[str, str],
    content_type: str = "",
) -> Tuple[Optional[int], Optional[float], str, Dict[str, str], Optional[str]]:
    """按指定方法发送原始请求，返回 (状态码, 耗时, 响应体, 响应头, 错误)。"""
    merged = {str(k): str(v) for k, v in (headers or {}).items()}
    if content_type and not any(key.lower() == "content-type" for key in merged):
        merged["Content-Type"] = content_type
    request = urllib.request.Request(
        target,
        data=(body.encode("utf-8") if body else None),
        headers=merged,
        method=(method or "GET").upper(),
    )
    opener = urllib.request.build_opener(_NoRedirect)
    started = time.perf_counter()
    try:
        with opener.open(request, timeout=timeout) as response:
            payload = response.read(1024 * 1024).decode("utf-8", errors="replace")
            status = response.getcode()
            response_headers = dict(response.headers.items())
    except urllib.error.HTTPError as exc:
        payload = exc.read(1024 * 1024).decode("utf-8", errors="replace")
        status = exc.code
        response_headers = dict(exc.headers.items()) if exc.headers else {}
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        elapsed = (time.perf_counter() - started) * 1000
        return None, round(elapsed, 2), "", {}, "{0}: {1}".format(type(exc).__name__, exc)
    elapsed = (time.perf_counter() - started) * 1000
    return status, round(elapsed, 2), payload, response_headers, None


def _decode_chunked(raw: str) -> str:
    """尽力解码 Transfer-Encoding: chunked 的原始报文；不像分块则原样返回。"""
    text = raw or ""
    if not re.match(r"^[0-9a-fA-F]{1,8}\r?\n", text):
        return text
    chunks: List[str] = []
    index = 0
    while index < len(text):
        line_end = text.find("\n", index)
        if line_end < 0:
            return raw
        size_text = text[index:line_end].strip().split(";")[0]
        try:
            size = int(size_text, 16)
        except ValueError:
            return raw
        index = line_end + 1
        if size == 0:
            break
        chunks.append(text[index : index + size])
        index += size + 2
    return "".join(chunks) if chunks else raw


def _cookie_pairs(cookie_text: str) -> List[Tuple[str, str]]:
    """把 ``a=1; b=2`` 或 Set-Cookie 风格文本拆成键值对。"""
    pairs: List[Tuple[str, str]] = []
    for chunk in re.split(r"[;\n]", cookie_text or ""):
        item = chunk.strip()
        if not item or "=" not in item:
            continue
        name, value = item.split("=", 1)
        name = name.strip()
        if not name or name.lower() in ("path", "domain", "expires", "max-age", "samesite", "secure", "httponly"):
            continue
        pairs.append((name, value.strip()))
    return pairs


def parse_pasted_request(raw: str) -> Dict[str, Any]:
    """解析粘贴的原始 HTTP 请求（请求行 + 头 + 可选体）。"""
    lines = (raw or "").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    method, url = "", ""
    if lines:
        first = lines[0].strip()
        head = first.split()
        if len(head) >= 2 and head[0].upper() in (
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT",
        ):
            method, url = head[0].upper(), head[1]
            lines = lines[1:]
    headers: Dict[str, str] = {}
    body_lines: List[str] = []
    in_body = False
    for line in lines:
        if in_body:
            body_lines.append(line)
            continue
        if not line.strip():
            in_body = True
            continue
        if ":" not in line:
            continue
        name, value = line.split(":", 1)
        name = name.strip()
        if not name:
            continue
        headers[name] = value.strip()
    return {"method": method, "url": url, "headers": headers, "body": "\n".join(body_lines).strip()}


def _absolute_url(url: str, headers: Dict[str, str]) -> str:
    """把相对路径结合 Host 头补全为绝对 URL；已有 scheme 时原样返回。"""
    text = (url or "").strip()
    if not text:
        return ""
    if text.startswith(("http://", "https://")):
        return text
    host = _header_lookup(headers, "host").strip()
    if not host:
        return text
    scheme = "https" if _header_lookup(headers, "x-forwarded-proto").lower() == "https" else "http"
    return "{0}://{1}{2}".format(scheme, host, text if text.startswith("/") else "/" + text)


FASTJSON_MARKERS = (
    "com.alibaba.fastjson",
    "fastjson2",
    "fastjson",
    "jsonexception",
    "autotype is not support",
    "not close json text",
    "syntax error, expect",
)

OTHER_MARKERS: Dict[str, Tuple[str, ...]] = {
    "jackson": (
        "com.fasterxml.jackson",
        "jsoneofexception",
        "unrecognizedpropertyexception",
        "unexpected end-of-input",
        "was expecting double-quote",
    ),
    "gson": ("com.google.gson", "malformedjsonexception", "jsonsyntaxexception"),
    "org.json": ("org.json.jsonexception", "unterminated string"),
    "hutool": ("cn.hutool.json", "jsonutil"),
}

FINGERPRINT_PROBES: Tuple[Tuple[str, str, str], ...] = (
    ("baseline", "标准 JSON 基线", '{"age":20,"name":"Bob"}'),
    ("broken_json", "损坏 JSON 错误特征", '{"age":20,"name":"Bob"'),
    ("autotype_marker", "Fastjson @type 错误特征", '{"@type":"whatever"}'),
    (
        "parse_features",
        "Fastjson 宽松语法特征",
        "{\"a\":new a(1),\"b\":x'11',\"c\":Set[{}],\"d\":\"\\u0000\\x00\"}",
    ),
    ("ref_resolve", "Fastjson $ref 自引用解析", '{"ext":"blue","name":{"$ref":"$.ext"}}'),
    ("diff_jackson_extra", "多余字段（Jackson 常报错）", '{"age":20,"name":"Bob","test":1}'),
    ("diff_jackson_quote", "单引号（Jackson 默认不支持）", "{\"age\":20,'name':'Bob'}"),
    ("diff_gson_float", "浮点精度（Gson 特征）", "{a:1.111111111111111111111111111}"),
)

STRONG_FINGERPRINT_PROBES = frozenset(
    {"broken_json", "autotype_marker", "parse_features", "ref_resolve"}
)

_REF_MARKERS = ('"name":"blue"', "'name':'blue'", '"name": "blue"')

HTTP_STATUS_HINTS: Dict[int, str] = {
    400: "请求被目标拒绝",
    401: "需要登录或鉴权",
    403: "访问被拒绝",
    404: "路径不存在",
    405: "请求方法不被允许（该路径可能只接受 GET）",
    406: "响应类型不被接受",
    415: "Content-Type 不被接受",
    429: "请求过于频繁",
    500: "服务端内部错误",
    502: "网关错误",
    503: "服务不可用",
}

# 这些状态码属于「方法 / 路径 / 网关」层面的整体拒绝，而不是 JSON 解析差异
INFRA_STATUSES = frozenset({404, 405, 406, 415, 429, 500, 501, 502, 503, 504})

ALL_PARSER_MARKERS: Tuple[str, ...] = FASTJSON_MARKERS + tuple(
    marker for markers in OTHER_MARKERS.values() for marker in markers
)


def _transport_block(
    pairs: Sequence[Tuple[str, Optional[int], str, Optional[str]]]
) -> Optional[str]:
    """判断探针是否根本没到达 JSON 解析器（连接失败，或 HTTP 层被整体拒绝）。

    只有「全部探针同属传输层失败、且没有任何解析器特征」时才判定为不可探测，
    避免把个别探针的 4xx/5xx 误当成解析差异，也避免把 405 登录页误判为 Fastjson。
    """
    if not pairs:
        return None
    statuses = [status for _, status, _, _ in pairs]
    if all(status is None for status in statuses):
        errors = [error for _, _, _, error in pairs if error]
        return "所有探针均无法连接到目标（{0}）".format(errors[0] if errors else "网络错误")
    if any(status is None for status in statuses):
        return None
    if any(_markers(body, ALL_PARSER_MARKERS) for _, _, body, _ in pairs):
        return None
    bodies = [(body or "").strip() for _, _, body, _ in pairs]
    if set(statuses) <= INFRA_STATUSES:
        code = collections.Counter(statuses).most_common(1)[0][0]
        return "所有探针均返回 HTTP {0}（{1}），未观察到任何 JSON 解析器特征".format(
            code, HTTP_STATUS_HINTS.get(code, "请求被拒绝")
        )
    if all(status >= 400 for status in statuses) and not any(bodies):
        code = collections.Counter(statuses).most_common(1)[0][0]
        return "所有探针均返回 HTTP {0}（{1}）且响应体为空，未观察到任何 JSON 解析器特征".format(
            code, HTTP_STATUS_HINTS.get(code, "请求被拒绝")
        )
    return None


TRANSPORT_BLOCK_HINT = (
    "请确认 URL 指向真正的 JSON 反序列化接口；"
    "若返回 405 可换其他请求方法（GET/PUT/PATCH）重试，"
    "也可从“抓包转换”页沿用浏览器的真实方法与请求体；"
    "若接口需要登录，请把已登录会话的 Cookie 作为请求头传入。"
)


def _detect_login_gate(
    ctx: dict, used_session_cookie: bool = False
) -> Optional[str]:
    """在探针被整层拒绝时，额外发一条 GET 确认目标是否把请求转发了登录页。

    靶场的登录拦截常表现为「POST 一律 405 空响应」：探针只发 POST 时看不到任何
    登录页特征，换方法重试时虽然拿到了登录页，却因为「响应不随 payload 变化」被
    当作无价值结果丢掉。这里单独补一条不带 payload 的 GET，用**完整响应体**判断
    是不是登录页——登录表单的 ``/user/login``、``password`` 等关键标记通常出现在
    正文深处，远超 evidence 里 500 字的截断长度。
    """
    status, _elapsed, body, error = _request(
        ctx["target"], "", ctx["timeout"], ctx["headers"], ctx["content_type"], "GET"
    )
    if error or status is None:
        return None
    hits = _markers(body, LOGIN_PAGE_MARKERS)
    if not hits:
        return None
    reason = "目标返回登录页（命中特征：{0}），请求未到达 JSON 解析器".format("、".join(hits))
    if used_session_cookie:
        return "{0}；已携带会话 Cookie 仍被拦截，Cookie 可能已过期或权限不足".format(reason)
    return "{0}；目标存在登录拦截，请先登录并携带会话 Cookie 后重试".format(reason)


_LOGIN_GATE_PREFIXES = {
    "detect": "无法探测：",
    "version": "版本未能收敛：",
    "expect": "未能完成探测：",
}


def _annotate_login_gate(ctx: dict, result: dict) -> dict:
    """探测没有拿到任何解析器特征时，确认并标注「登录拦截」。

    只改结论与提示，不动 evidence：判定依据来自单独补投的 GET，不应混进探针明细。
    """
    evidence = result.get("evidence") or []
    if any((item.get("matched") or []) for item in evidence):
        return result
    reason = _detect_login_gate(ctx, bool(ctx["extras"].get("session_cookie")))
    if not reason:
        return result
    prefix = _LOGIN_GATE_PREFIXES.get(result.get("mode", "detect"), "无法探测：")
    result["login_gate"] = True
    result["summary"] = "{0}{1}".format(prefix, reason)
    result["notes"] = ["登录拦截：{0}".format(reason), LOGIN_GATE_HINT]
    return result


def _clean_session_cookie(value: Any) -> str:
    """规范化会话 Cookie：允许直接粘贴 ``Cookie: a=1; b=2`` 整行。"""
    text = str(value or "").strip()
    if text.lower().startswith("cookie:"):
        text = text.split(":", 1)[1].strip()
    return text


def _merge_session_cookie(
    headers: Dict[str, str], session_cookie: str
) -> Tuple[Dict[str, str], bool]:
    """把会话 Cookie 并入请求头，返回 (新请求头, 是否新增了键)。

    已有的同名 Cookie 不会被覆盖：抓包得到的 JWT_TOKEN 与配置里的 JSESSIONID 往往
    各自只出现一次，按名字补齐即可，避免把已登录会话整段替换掉。
    """
    pairs = _cookie_pairs(session_cookie)
    if not pairs:
        return {str(k): str(v) for k, v in (headers or {}).items()}, False
    merged = {str(k): str(v) for k, v in (headers or {}).items()}
    cookie_key = next((key for key in merged if key.lower() == "cookie"), "")
    existing = _cookie_pairs(merged.get(cookie_key, ""))
    names = {name.lower() for name, _ in existing}
    added = False
    for name, value in pairs:
        if name.lower() in names:
            continue
        existing.append((name, value))
        names.add(name.lower())
        added = True
    if added:
        merged[cookie_key or "Cookie"] = "; ".join(
            "{0}={1}".format(name, value) for name, value in existing
        )
    return merged, added


def _fingerprint(ctx: dict) -> dict:
    target = ctx["target"]
    request_headers = ctx["headers"]
    timeout = ctx["timeout"]
    content_type = ctx["content_type"]

    evidence: List[ProbeResult] = []
    fastjson_score = 0.0
    other_scores: Dict[str, float] = {name: 0.0 for name in OTHER_MARKERS}
    bodies: Dict[str, str] = {}

    sent = _send_probes(
        FINGERPRINT_PROBES, target, timeout, request_headers, content_type, ctx["request_method"]
    )
    for (probe_id, description, payload), (status, elapsed, body, error) in zip(
        FINGERPRINT_PROBES, sent
    ):
        bodies[probe_id] = body
        matched = _markers(body, FASTJSON_MARKERS)
        for library, markers in OTHER_MARKERS.items():
            hits = _markers(body, markers)
            if hits:
                other_scores[library] += float(len(hits))
                matched.extend("{0}:{1}".format(library, hit) for hit in hits)

        if probe_id in STRONG_FINGERPRINT_PROBES and matched:
            fastjson_score += 2.0
        elif probe_id == "baseline" and status is not None and status < 500:
            fastjson_score += 0.25

        if probe_id == "diff_jackson_extra":
            if _markers(body, OTHER_MARKERS["jackson"]):
                other_scores["jackson"] += 1.0
            elif status is not None and status < 400:
                fastjson_score += 0.5
        if probe_id == "diff_jackson_quote" and status is not None and status < 400:
            fastjson_score += 0.5
        if probe_id == "diff_gson_float" and _markers(body, OTHER_MARKERS["gson"]):
            other_scores["gson"] += 1.0

        evidence.append(
            ProbeResult(
                probe_id=probe_id,
                category="fingerprint",
                description=description,
                status=status,
                elapsed_ms=elapsed,
                errored=None,
                matched=matched,
                response_excerpt=_excerpt(body),
                error=error,
            )
        )

    ref_body = bodies.get("ref_resolve", "")
    if ref_body and any(marker in ref_body for marker in _REF_MARKERS):
        fastjson_score += 2.0
        for item in evidence:
            if item.probe_id == "ref_resolve":
                item.matched.append("$ref 被解析为字面量")

    strongest_other = max(other_scores, key=other_scores.get)
    strongest_other_score = other_scores[strongest_other]
    confidence = min(0.99, fastjson_score / 9.0)
    is_fastjson = fastjson_score >= 2.0 and fastjson_score > strongest_other_score
    pairs = [(item.probe_id, item.status, item.response_excerpt, item.error) for item in evidence]
    transport_block = _transport_block(pairs)
    uniform_block = None
    if transport_block is None and not any(item.matched for item in evidence):
        # 没有任何特征命中时，再确认是不是「所有探针拿到同一份内容」的静态页
        uniform_block = _uniform_response_block(pairs)
    if (transport_block or uniform_block) and not is_fastjson:
        confidence = 0.0
        is_fastjson = False
        transport_block = transport_block or uniform_block
    elif not is_fastjson:
        confidence = min(confidence, 0.49)

    if is_fastjson:
        summary = "响应包含 Fastjson 特征，建议在授权环境中继续核对依赖版本与安全配置。"
    elif transport_block:
        summary = "无法探测：{0}。{1}".format(transport_block, TRANSPORT_BLOCK_HINT)
    elif strongest_other_score > 0:
        summary = "未确认 Fastjson，响应更接近 {0} 特征。".format(strongest_other)
    else:
        summary = "未确认 Fastjson，可能是路径、请求格式或错误响应未暴露解析器特征。"

    result = {
        "mode": "detect",
        "target": target,
        "request_method": ctx["request_method"],
        "is_fastjson": is_fastjson,
        "confidence": round(confidence, 3),
        "scores": {
            "fastjson": round(fastjson_score, 3),
            **{name: round(score, 3) for name, score in other_scores.items()},
        },
        "summary": summary,
        "evidence": [asdict(item) for item in evidence],
        # 这三条是对**所有**目标都成立的通用边界，合成一行即可；
        # 过去逐条打印会占掉结果区 5 行，把真正的探测结论挤下去
        "limitations": [
            "结果是远程响应指纹、不等同于漏洞确认；仅限已授权目标；"
            "工具本身不执行利用链 / 命令执行 / 文件读写 / 内存马。",
        ],
    }
    if transport_block:
        result["notes"] = ["探测未生效：{0}".format(transport_block), TRANSPORT_BLOCK_HINT]
    return result
VERSION_RE = re.compile(r"fastjson-version\s+(\d+\.\d+(?:\.\d+)?)", re.I)

VERSION_ERROR_MARKERS = (
    "autotype is not support",
    "com.alibaba.fastjson.jsonexception",
    "com.alibaba.fastjson2.jsonexception",
    "syntax error",
    "type not match",
    "illegal character",
    "illegal syntax",
)

SAFEMODE_MARKERS = (
    "safemode not support autotype",
    "safe mode not support autotype",
)

_OPAQUE_OK_FALSE_RE = re.compile(r'^\s*\{\s*"ok"\s*:\s*false\s*\}\s*$', re.I)
_OPAQUE_ERROR_RE = re.compile(r'^\s*\{\s*"error"\s*:', re.I)


def _version_response_errored(
    status: Optional[int],
    body: str,
    error_sig: Optional[Tuple[int, str]] = None,
) -> Optional[bool]:
    """这条探针是否拿到了「解析器报错」的响应。

    返回 `None` 表示**根本没拿到响应**（连接中断 / 超时）。这类探针既不能算报错、
    也不能算正常：布尔差分的结论完全建立在「基线正常、探针报错」之上，把一个没有
    响应的探针记成「未报错」会让后续区间推断凭空成立，实测会推出 `1.2.70-1.2.80`
    这种彻头彻尾的假区间。因此这里刻意用三态而不是 `False`。
    """
    if status is None:
        return None
    text = body or ""
    if status >= 400 and _markers(text, VERSION_ERROR_MARKERS):
        # 5xx 但响应体里出现了解析器特征（如统一错误页包了 fastjson 异常）时仍算命中
        return True
    if status >= 500:
        # 5xx 是「网关 / 服务端整体拒绝」（502 代理错误、503 不可用、501 不支持的方法），
        # 与本次 payload 无关，不能当成解析器报错参与布尔差分，否则会凭空推出区间。
        return None
    if status >= 400:
        return True
    lower = text.lower()
    if _markers(text, VERSION_ERROR_MARKERS):
        return True
    stripped = text.strip()
    stripped_lower = stripped.lower()
    if stripped_lower in ("error", '"error"', "'error'"):
        return True
    if _OPAQUE_OK_FALSE_RE.match(stripped):
        return True
    if _OPAQUE_ERROR_RE.match(stripped) and (
        len(stripped) < 200 or "exception" in lower or "syntax" in lower
    ):
        return True
    if error_sig is not None and (status, stripped[:300]) == error_sig:
        return True
    return False


def _version_probes(dns_hosts: Optional[Dict[str, str]] = None) -> List[Tuple[str, str, str, str]]:
    probes: List[Tuple[str, str, str, str]] = [
        ("baseline_ok", "control", "合法 JSON；建立正常响应指纹", '{"x":1}'),
        ("negative_control", "control", "残缺 JSON；建立报错指纹", '{"@type":'),
        (
            "autotype_class",
            "autotype",
            "java.lang.Class 空 val；AutoType 开启时报错",
            '{"xxx":{"@type":"java.lang.Class","val":""}}',
        ),
        (
            "autotype_random",
            "autotype",
            "Random.String；AutoType 关闭时报错",
            '{"xxx":{"@type":"Random.String"}}',
        ),
        (
            "safemode_string",
            "safemode",
            "java.lang.String 畸形；SafeMode 相关报错",
            '{"zero":{"@type":"java.lang.String"""}}}',
        ),
        (
            "autocloseable_exact",
            "exact",
            "残缺 AutoCloseable；期望回显 fastjson-version",
            '{"@type":"java.lang.AutoCloseable"',
        ),
        (
            "probe_1_2_83",
            "exact",
            "Test.TestException；仅 1.2.83 通常不报错",
            '{"xxx":{"@type":"Test.TestException"}}',
        ),
        (
            "offline_exception",
            "offline",
            "报错≈1.2.25-1.2.80；不报错≈1.2.24/1.2.83",
            '{"zero":{"@type":"java.lang.Exception","@type":"org.XxException"}}',
        ),
        (
            "offline_autocloseable",
            "offline",
            "报错≈1.2.70-1.2.83；不报错≈1.2.24-1.2.68",
            '{"zero":{"@type":"java.lang.AutoCloseable","@type":"java.io.ByteArrayOutputStream"}}',
        ),
        (
            "offline_class_jdbc",
            "offline",
            "报错≈1.2.48-1.2.83；不报错≈1.2.24-1.2.47",
            '{"a":{"@type":"java.lang.Class","val":"com.sun.rowset.JdbcRowSetImpl"},'
            '"b":{"@type":"com.sun.rowset.JdbcRowSetImpl"}}',
        ),
        (
            "offline_jdbc",
            "offline",
            "报错≈1.2.25-1.2.83；不报错≈1.2.24",
            '{"zero":{"@type":"com.sun.rowset.JdbcRowSetImpl"}}',
        ),
    ]
    if dns_hosts:
        probes.extend(_version_dns_probes(dns_hosts))
    return probes


def _version_dns_probes(dns_hosts: Dict[str, str]) -> List[Tuple[str, str, str, str]]:
    le47 = _strip_dns_host(dns_hosts["le47"])
    le68 = _strip_dns_host(dns_hosts["le68"])
    d80a = _strip_dns_host(dns_hosts["d80a"])
    d80b = _strip_dns_host(dns_hosts["d80b"])
    return [
        (
            "dns_le_1_2_47",
            "dns",
            "DNS 命中≈<=1.2.47",
            '[{"@type":"java.lang.Class","val":"java.io.ByteArrayOutputStream"},'
            '{"@type":"java.io.ByteArrayOutputStream"},'
            '{{"@type":"java.net.InetSocketAddress"{{"address":,"val":"' + le47 + '"}}}}]',
        ),
        (
            "dns_le_1_2_68",
            "dns",
            "DNS 命中≈<=1.2.68",
            '[{"@type":"java.lang.AutoCloseable","@type":"java.io.ByteArrayOutputStream"},'
            '{"@type":"java.io.ByteArrayOutputStream"},'
            '{{"@type":"java.net.InetSocketAddress"{{"address":,"val":"' + le68 + '"}}}}]',
        ),
        (
            "dns_1_2_80_83",
            "dns",
            "仅 d80a≈1.2.80；d80a+d80b≈1.2.83",
            '[{"@type":"java.lang.Exception","@type":"com.alibaba.fastjson.JSONException",'
            '"x":{{"@type":"java.net.InetSocketAddress"{{"address":,"val":"' + d80a + '"}}}}}},'
            '{{"@type":"java.lang.Exception","@type":"com.alibaba.fastjson.JSONException",'
            '"message":{{"@type":"java.net.InetSocketAddress"{{"address":,"val":"' + d80b + '"}}}}}}]',
        ),
    ]


def _infer_autotype(flags: Dict[str, Any]) -> Optional[bool]:
    class_err = flags.get("autotype_class")
    random_err = flags.get("autotype_random")
    # 三态：None 表示该探针没拿到响应，不能与「未报错」混为一谈，
    # 否则一个连接中断就能凭空推出“AutoType 开启/关闭”。
    if class_err is None or random_err is None:
        return None
    if class_err and not random_err:
        return True
    if random_err and not class_err:
        return False
    return None


def _infer_version(flags: Dict[str, Any], reported: Optional[str]) -> dict:
    exception_err = flags.get("offline_exception")
    autocloseable_err = flags.get("offline_autocloseable")
    class_jdbc_err = flags.get("offline_class_jdbc")
    jdbc_err = flags.get("offline_jdbc")

    detail: Optional[str] = None
    version_range: Optional[str] = None
    confidence = 0.0

    known = all(
        value is not None
        for value in (exception_err, autocloseable_err, class_jdbc_err, jdbc_err)
    )
    # 只要有一条探针没拿到响应就整体不收敛：区间推断用四条布尔值做联合判断，
    # 缺任何一条都可能落进错误的档位（实测会推出 `1.2.70-1.2.80` 的假区间）。
    if known:
        if exception_err is False:
            if jdbc_err is False and class_jdbc_err is False:
                detail = "≈1.2.24"
                version_range = "<=1.2.47"
                confidence = 0.75
            else:
                detail = "1.2.83"
                version_range = "1.2.83"
                confidence = 0.7
        elif autocloseable_err is False and class_jdbc_err is False and jdbc_err is True:
            detail = "1.2.25-1.2.47"
            version_range = "<=1.2.47"
            confidence = 0.85
        elif autocloseable_err is False and class_jdbc_err is True and jdbc_err is True:
            detail = "1.2.48-1.2.68"
            version_range = "<=1.2.68"
            confidence = 0.85
        elif autocloseable_err is True and class_jdbc_err is True and jdbc_err is True:
            detail = "1.2.70-1.2.80"
            version_range = "<=1.2.80"
            confidence = 0.8

    if not known and reported:
        confidence = 0.0
    if reported:
        confidence = max(confidence, 0.9)

    parts: List[str] = []
    if reported:
        parts.append("回显版本 {0}".format(reported))
    if detail:
        parts.append("布尔探针区间 {0}".format(detail))
    if version_range:
        parts.append("PoC 档位 {0}".format(version_range))
    if not parts:
        parts.append("版本未能收敛：请确认端点为 Fastjson 反序列化点且存在报错差异")

    return {
        "reported_version": reported,
        "version_detail": detail,
        "version_range": version_range,
        "confidence": round(confidence, 3),
        "summary": "；".join(parts),
    }
def _version(ctx: dict, dns_hosts: Optional[Dict[str, str]] = None) -> dict:
    target = ctx["target"]
    request_headers = ctx["headers"]
    timeout = ctx["timeout"]
    content_type = ctx["content_type"]
    extras = ctx["extras"]
    dns_hosts = dns_hosts or {}

    evidence: List[ProbeResult] = []
    flags: Dict[str, Any] = {}
    parsed: Dict[str, Any] = {}
    reported: Optional[str] = None
    error_sig: Optional[Tuple[int, str]] = None

    version_probes = _version_probes(dns_hosts)
    sent = _send_probes(
        version_probes, target, timeout, request_headers, content_type, ctx["request_method"]
    )
    for (probe_id, category, description, payload), (status, elapsed, body, error) in zip(
        version_probes, sent
    ):
        errored = _version_response_errored(status, body, error_sig)
        flags[probe_id] = errored
        parsed[probe_id] = {
            "status": status,
            "errored": errored,
            "excerpt": _excerpt(body, 200),
            "error": error,
        }

        if probe_id == "negative_control" and error_sig is None and status is not None:
            error_sig = (status, (body or "").strip()[:300])

        if probe_id == "autocloseable_exact":
            found = VERSION_RE.search(body or "")
            if found:
                reported = found.group(1)

        evidence.append(
            ProbeResult(
                probe_id=probe_id,
                category=category,
                description=description,
                status=status,
                elapsed_ms=elapsed,
                errored=errored,
                matched=_markers(body, VERSION_ERROR_MARKERS),
                response_excerpt=_excerpt(body),
                error=error,
            )
        )

    ceye_config = _ceye_config(ctx) if _flag(extras, "ceye_enabled", True) else None
    dns_hits: Dict[str, bool] = {}
    dns_records: List[dict] = []
    filter_id = _dns_safe_label(extras.get("dns_filter") or ("fj" + uuid.uuid4().hex[:12]))
    notes: List[str] = []

    if dns_hosts:
        if ceye_config:
            wait = float(extras.get("dns_wait") or DEFAULT_DNS_WAIT)
            dns_records = _ceye_wait(ceye_config, filter_id, wait)
            names = " ".join((row.get("name") or "").lower() for row in dns_records)
            for key, host in dns_hosts.items():
                tag = _dns_safe_label(key, 8)
                head = _strip_dns_host(host).split(".")[0].lower()
                dns_hits[key] = bool(names) and (tag in names or (head and head in names))
            if not dns_records:
                notes.append("CEYE 未返回记录：确认 Token/域名，或目标可能不出网。")
        else:
            notes.append("未配置 CEYE：DNS 探针已投递，请到 DNSLog 平台按 filter 手动核对。")

    result = _infer_version(flags, reported)
    result.update(
        {
            "mode": "version",
            "target": target,
            "request_method": ctx["request_method"],
            "autotype_enabled": _infer_autotype(flags),
            "safemode_enabled": flags.get("safemode_string"),
            "flags": flags,
            "probes": parsed,
            "dns_filter": filter_id,
            "dns_hits": dns_hits,
            "dns_records": dns_records,
            "notes": notes,
            "evidence": [asdict(item) for item in evidence],
            "limitations": [
                "布尔探针无法区分 1.2.70-1.2.72 与 1.2.73-1.2.80。",
                "生产环境若无报错差异（统一 500/error），离线区间会失真。",
                "DNS 版本探针依赖目标出网与 autoType 状态，需 CEYE 侧确认。",
            ],
        }
    )
    # 全部探针都在 HTTP 层被拒绝（例如 405 登录页）时，布尔差异没有意义，
    # 必须回退为「未能探测」而不是给出一个看似确定的版本区间。
    transport_block = _transport_block(
        [(item.probe_id, item.status, item.response_excerpt, item.error) for item in evidence]
    )
    if transport_block:
        result.update(
            {
                "reported_version": None,
                "version_detail": None,
                "version_range": None,
                "confidence": 0.0,
                "autotype_enabled": None,
                "safemode_enabled": None,
                "summary": "版本未能收敛：{0}。{1}".format(transport_block, TRANSPORT_BLOCK_HINT),
            }
        )
        result["notes"] = list(notes) + ["探测未生效：{0}".format(transport_block), TRANSPORT_BLOCK_HINT]
    return result
FEATURE_TYPE = "com.alibaba.fastjson.support.geo.Feature"
DEFAULT_BASE_BODY = '{"age":20,"name":"Bob"}'
HTTP_METHODS: Tuple[str, ...] = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
CONVERT_FORMATS: Tuple[str, ...] = (
    "curl",
    "raw",
    "json",
    "cookie-header",
    "cookie-json",
    "cookie-netscape",
)


def _header_lookup(headers: Dict[str, str], name: str) -> str:
    target = name.lower()
    for key, value in (headers or {}).items():
        if str(key).lower() == target:
            return str(value)
    return ""


def _subject_json(subject: Dict[str, Any]) -> Dict[str, Any]:
    headers = subject.get("headers") or {}
    query = subject.get("query") or {}
    cookies = _cookie_pairs(_header_lookup(headers, "cookie"))
    return {
        "url": subject.get("url", ""),
        "method": subject.get("method", "POST"),
        "headers": {str(k): str(v) for k, v in headers.items()},
        "content_type": subject.get("content_type", ""),
        "query": query,
        "cookies": {name: value for name, value in cookies},
        "body": subject.get("body", ""),
    }


def _curl_quote(value: str) -> str:
    return "'" + (value or "").replace("'", "'\\''") + "'"


def _render_curl(subject: Dict[str, Any]) -> str:
    parts = ["curl -i -sS -X {0}".format(subject.get("method") or "POST")]
    for name, value in (subject.get("headers") or {}).items():
        parts.append("-H {0}".format(_curl_quote("{0}: {1}".format(name, value))))
    body = subject.get("body") or ""
    if body:
        parts.append("--data-raw {0}".format(_curl_quote(body)))
    parts.append(_curl_quote(subject.get("url", "")))
    return " \\\n  ".join(parts)


def _render_raw_request(subject: Dict[str, Any]) -> str:
    url = subject.get("url", "")
    parsed = urllib.parse.urlsplit(url)
    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query
    lines = ["{0} {1} HTTP/1.1".format(subject.get("method") or "POST", path)]
    headers = dict(subject.get("headers") or {})
    host = parsed.netloc
    if host and not any(key.lower() == "host" for key in headers):
        headers["Host"] = host
    lowered = {key.lower() for key in headers}
    if subject.get("content_type") and "content-type" not in lowered:
        headers["Content-Type"] = subject["content_type"]
    body = subject.get("body") or ""
    if body and "content-length" not in lowered:
        headers["Content-Length"] = str(len(body.encode("utf-8")))
    lines.extend("{0}: {1}".format(name, value) for name, value in headers.items())
    lines.append("")
    lines.append(body)
    return "\n".join(lines)


def _render_cookie_netscape(subject: Dict[str, Any], pairs: List[Tuple[str, str]]) -> str:
    parsed = urllib.parse.urlsplit(subject.get("url", ""))
    domain = parsed.hostname or ""
    lines = ["# Netscape HTTP Cookie File"]
    for name, value in pairs:
        lines.append("\t".join([domain, "FALSE", "/", "FALSE", "0", name, value]))
    return "\n".join(lines)


def convert_report(subject: Dict[str, Any], target: str) -> str:
    """把抓包/粘贴得到的请求转换为常用格式文本。"""
    headers = subject.get("headers") or {}
    cookie_pairs = _cookie_pairs(_header_lookup(headers, "cookie"))
    cookie_header = "; ".join("{0}={1}".format(name, value) for name, value in cookie_pairs)
    cookie_json = json.dumps({name: value for name, value in cookie_pairs}, ensure_ascii=False, indent=2)
    payload = _subject_json(subject)
    if target == "curl":
        return _render_curl(subject)
    if target == "raw":
        return _render_raw_request(subject)
    if target == "cookie-header":
        return cookie_header or "（未在请求头中发现 Cookie）"
    if target == "cookie-json":
        return cookie_json
    if target == "cookie-netscape":
        return _render_cookie_netscape(subject, cookie_pairs)
    return json.dumps(payload, ensure_ascii=False, indent=2)

_EXPECT_ERROR_MARKERS = VERSION_ERROR_MARKERS + (
    "can not cast",
    "deserialize",
    "parseexception",
    "type not match",
)


def _expect_errored(status: Optional[int], body: str) -> Optional[bool]:
    """与 `_version_response_errored` 同理：没拿到响应时返回 `None`（未知），而不是 `False`。"""
    if status is None:
        return None
    if status >= 400 and _markers(body, _EXPECT_ERROR_MARKERS):
        return True
    if status >= 500:
        return None
    if status >= 400:
        return True
    lower = (body or "").lower()
    if '"error"' in lower and ("exception" in lower or "syntax" in lower):
        return True
    return False


def _inject(base_text: str, probe_inner: str) -> str:
    base_text = (base_text or "").strip()
    if not (base_text.startswith("{") and base_text.endswith("}")):
        raise ValueError("base_body 必须是 JSON 对象")
    inner = base_text[1:-1].strip()
    if not inner:
        return "{" + probe_inner + "}"
    return "{" + inner + "," + probe_inner + "}"


def _expect_probes(base_body: str) -> List[Tuple[str, str, str, str]]:
    text = (base_body or "").strip() or DEFAULT_BASE_BODY
    try:
        parsed = json.loads(text)
    except ValueError as exc:
        raise ValueError("base_body 不是合法 JSON: {0}".format(exc))
    if not isinstance(parsed, dict):
        raise ValueError("base_body 须为 JSON 对象")
    baseline = json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))

    return [
        ("baseline", "control", "原始业务参数基线", baseline),
        (
            "feature_type",
            "feature",
            "注入 @type=Feature；报错提示存在期望类（或版本 <1.2.68 类不存在）",
            _inject(baseline, '"@type":"' + FEATURE_TYPE + '"'),
        ),
        (
            "empty_key_root",
            "empty_key",
            "根级 { {}: {} }；报错且类型非 Map → 存在期望类",
            _inject(baseline, "{}:{}"),
        ),
        (
            "empty_key_nested",
            "control",
            '嵌套 "test": { { {}: {} }: "" } 对照；通常不报错',
            _inject(baseline, '"test":{{{}:{}}:""}'),
        ),
    ]


def _expect(ctx: dict, base_body: str = "") -> dict:
    target = ctx["target"]
    request_headers = ctx["headers"]
    timeout = ctx["timeout"]
    content_type = ctx["content_type"]
    extras = ctx["extras"]
    base = (base_body or "").strip() or extras.get("base_body") or DEFAULT_BASE_BODY
    probes = _expect_probes(base)

    evidence: List[ProbeResult] = []
    flags: Dict[str, Any] = {}
    sent = _send_probes(
        probes, target, timeout, request_headers, content_type, ctx["request_method"]
    )
    for (probe_id, category, description, payload), (status, elapsed, body, error) in zip(
        probes, sent
    ):
        errored = _expect_errored(status, body)
        flags[probe_id] = errored
        evidence.append(
            ProbeResult(
                probe_id=probe_id,
                category=category,
                description=description,
                status=status,
                elapsed_ms=elapsed,
                errored=errored,
                matched=_markers(body, _EXPECT_ERROR_MARKERS),
                response_excerpt=_excerpt(body),
                error=error,
            )
        )

    baseline_err = flags.get("baseline")
    feature_err = flags.get("feature_type")
    empty_err = flags.get("empty_key_root")
    nested_err = flags.get("empty_key_nested")
    transport_block = _transport_block(
        [(item.probe_id, item.status, item.response_excerpt, item.error) for item in evidence]
    )

    has_expect: Optional[bool] = None
    expect_not_map: Optional[bool] = None
    lt_1_2_68: Optional[bool] = None
    confidence = 0.0
    missing_probes = False

    if transport_block:
        interpretation = "所有探针都在 HTTP 层被拒绝，期望类信号不可信"
        confidence = 0.0
    elif None in (baseline_err, feature_err, empty_err, nested_err):
        # 有探针根本没拿到响应（连接中断 / 超时）时不能进入下面的矩阵：
        # 矩阵的前提是「四条探针都真的被目标处理过」，缺任何一条都可能得出反向结论。
        missing = [
            name
            for name, value in (
                ("基线", baseline_err),
                ("Feature", feature_err),
                ("根级空键", empty_err),
                ("嵌套对照", nested_err),
            )
            if value is None
        ]
        interpretation = "以下探针未拿到响应（连接中断或超时）：{0}".format(
            "、".join(missing)
        )
        confidence = 0.0
        missing_probes = True
    elif baseline_err:
        interpretation = "基线参数即报错，期望类信号不可信；请更换接近业务的合法 base_body"
        confidence = 0.2
    elif nested_err and empty_err:
        if feature_err:
            lt_1_2_68 = True
            interpretation = "空键语法均报错且 Feature 报错：可能版本 <1.2.68，或目标拒绝空键语法"
            confidence = 0.45
        else:
            interpretation = "空键语法均报错：目标可能拒绝该语法，期望类无法确认"
            confidence = 0.35
    elif feature_err and empty_err and not nested_err:
        has_expect, expect_not_map = True, True
        confidence = 0.9
        interpretation = "Feature 与根级空键均报错，嵌套对照正常 → 存在期望类且非 Map"
    elif feature_err and not empty_err:
        has_expect, expect_not_map, lt_1_2_68 = False, False, True
        confidence = 0.8
        interpretation = "仅 Feature 报错而空键正常 → 倾向版本 <1.2.68（Feature 类不存在），未见期望类"
    elif (not feature_err) and empty_err and not nested_err:
        has_expect, expect_not_map = True, True
        confidence = 0.75
        interpretation = "根级空键报错且嵌套对照正常 → 存在期望类且非 Map"
    elif (not feature_err) and (not empty_err):
        has_expect, expect_not_map = False, False
        confidence = 0.85
        interpretation = "Feature 与空键均不报错 → 倾向无期望类，或期望类型为 Map/其子类"
    else:
        interpretation = "探针结果组合无法归入已知矩阵"
        confidence = 0.3

    if has_expect is True:
        head = "判定存在期望类"
    elif has_expect is False:
        head = "判定不存在期望类（或期望为 Map）"
    else:
        head = "未能判定是否存在期望类"

    summary = "{0}；{1}；置信度 {2:.2f}".format(head, interpretation, confidence)
    if lt_1_2_68:
        summary += "；版本可能 <1.2.68"
    if transport_block:
        summary = "未能完成探测：{0}。{1}".format(transport_block, TRANSPORT_BLOCK_HINT)
    if missing_probes:
        summary = "未能完成探测：{0}。请确认目标可达、网络稳定后重试。".format(interpretation)

    result = {
        "mode": "expect",
        "target": target,
        "request_method": ctx["request_method"],
        "base_body": base,
        "has_expect_class": has_expect,
        "expect_not_map": expect_not_map,
        "version_lt_1_2_68_hint": lt_1_2_68,
        "confidence": round(confidence, 3),
        "flags": flags,
        "summary": summary,
        "evidence": [asdict(item) for item in evidence],
        "limitations": [
            "Feature 类自 1.2.68 引入，低版本会因类不存在报错，需与空键探针交叉解读。",
            "空键根级报错且期望类型不是 Map 时，倾向存在期望类。",
            "请尽量使用接近真实业务的原始请求参数作为 base_body。",
        ],
    }
    if transport_block:
        result["notes"] = ["探测未生效：{0}".format(transport_block), TRANSPORT_BLOCK_HINT]
    return result
DNS_PROBE_TEMPLATES: Tuple[Tuple[str, str, str], ...] = (
    (
        "dns_inet4_address",
        "Inet4Address DNS 探针",
        '{{"@type":"java.net.Inet4Address","val":"{host}"}}',
    ),
    (
        "dns_inet_socket",
        "InetSocketAddress DNS 探针",
        '{{"@type":"java.net.InetSocketAddress"{{"address":,"val":"{host}"}}}}',
    ),
    (
        "dns_url_key",
        "java.net.URL 作为 Map key 的 DNS 探针",
        '{{{{"@type":"java.net.URL","val":"http://{host}"}}:"a"}}',
    ),
    (
        "dns_fastjson_exception",
        "java.lang.Exception 兜底 DNS 探针（1.2.73-1.2.83 场景）",
        '[{{"@type":"java.lang.Exception","@type":"com.alibaba.fastjson.JSONException",'
        '"x":{{"@type":"java.net.InetSocketAddress"{{"address":,"val":"{host}"}}}}}}]',
    ),
)


def _dns_probes(host: str) -> List[Tuple[str, str, str, str]]:
    host = _strip_dns_host(host)
    if not host:
        raise ValueError("DNS 探针需要提供 dnslog 主机名")
    return [
        (probe_id, "dns", description, template.format(host=host))
        for probe_id, description, template in DNS_PROBE_TEMPLATES
    ]


def _dns(ctx: dict, host: str = "", confirm: bool = True) -> dict:
    target = ctx["target"]
    request_headers = ctx["headers"]
    timeout = ctx["timeout"]
    content_type = ctx["content_type"]
    extras = ctx["extras"]
    host = _strip_dns_host(host or extras.get("dnslog_host") or "")

    # confirm=False 时只投递 DNS 探针，不调用 CEYE 接口
    ceye_config = _ceye_config(ctx) if confirm else None
    filter_id = _dns_safe_label(extras.get("dns_filter") or ("fj" + uuid.uuid4().hex[:12]))
    wait = float(extras.get("dns_wait") or DEFAULT_DNS_WAIT)

    if host:
        probes = _dns_probes(host)
    else:
        probes = [
            (
                "autotype_probe",
                "dns",
                "未提供 dnslog 主机：改发 @type 探针，观察报错与耗时差异",
                '{"@type":"java.net.Inet4Address","val":"127.0.0.1"}',
            )
        ]

    evidence: List[ProbeResult] = []
    dns_records: List[dict] = []
    dns_hits: Dict[str, bool] = {}
    notes: List[str] = []

    if host and ceye_config:
        dns_records = _ceye_wait(ceye_config, filter_id, wait)
        names = " ".join((row.get("name") or "").lower() for row in dns_records)
        if not dns_records:
            notes.append("CEYE 未返回记录：确认 Token / 域名，或目标可能不出网。")
        for probe_id, _, _, _ in probes:
            dns_hits[probe_id] = bool(names)
    elif host:
        notes.append(
            "已关闭 CEYE 确认：仅投递 DNS 探针，请在 DNSLog 平台按 filter 核对。"
            if not confirm
            else "未配置 CEYE：探针已投递，请到 DNSLog 平台按 filter 手动核对。"
        )

    sent = _send_probes(
        probes, target, timeout, request_headers, content_type, ctx["request_method"]
    )
    for (probe_id, category, description, payload), (status, elapsed, body, error) in zip(
        probes, sent
    ):
        evidence.append(
            ProbeResult(
                probe_id=probe_id,
                category=category,
                description=description,
                status=status,
                elapsed_ms=elapsed,
                errored=_version_response_errored(status, body),
                matched=_markers(body, ("fastjson", "autotype", "jsonexception")),
                response_excerpt=_excerpt(body),
                error=error,
            )
        )

    if host and dns_records:
        summary = "DNS 探针已投递，CEYE 确认 {0} 条记录".format(len(dns_records))
    elif host and ceye_config:
        summary = "DNS 探针已投递，CEYE 暂未确认记录；可稍后重查"
    elif host:
        summary = (
            "DNS 探针已投递；已关闭 CEYE 确认，请在 DNSLog 平台核对"
            if not confirm
            else "DNS 探针已投递；未配置 CEYE，请在 DNSLog 平台核对"
        )
    else:
        summary = "未提供 dnslog 主机，仅完成 @type 探针行为对比"

    return {
        "mode": "dns",
        "target": target,
        "dnslog_host": host,
        "dns_filter": filter_id,
        "dns_hits": dns_hits,
        "dns_records": dns_records,
        "notes": notes,
        "summary": summary,
        "evidence": [asdict(item) for item in evidence],
        "limitations": [
            "DNS 探针依赖目标可出网；不出网时仅能观察报错与耗时。",
            "命中 DNS 只能说明类加载 / 网络访问发生，不代表漏洞可被利用。",
        ],
    }


def _ceye_config(ctx: dict) -> Optional[Dict[str, str]]:
    extras = ctx.get("extras") or {}
    token = str(
        extras.get("ceye_token")
        or os.environ.get("CEYE_TOKEN")
        or os.environ.get("FJ_CEYE_TOKEN")
        or _config_file().get("ceye_token")
        or ""
    ).strip()
    if not token:
        return None
    domain = str(
        extras.get("ceye_domain")
        or os.environ.get("CEYE_DOMAIN")
        or os.environ.get("FJ_CEYE_DOMAIN")
        or _config_file().get("ceye_domain")
        or DEFAULT_CEYE_DOMAIN
    ).strip()
    api = str(
        extras.get("ceye_api")
        or os.environ.get("CEYE_API")
        or _config_file().get("ceye_api")
        or DEFAULT_CEYE_API
    ).strip()
    return {"token": token, "domain": domain, "api": api}


def _ceye_query(config: Dict[str, str], filter_text: str, record_type: str = "dns") -> List[dict]:
    query = urllib.parse.urlencode(
        {"token": config["token"], "type": record_type, "filter": (filter_text or "")[:20]}
    )
    url = "{0}?{1}".format(config["api"], query)
    last_error: Optional[Exception] = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                payload = json.loads(response.read().decode("utf-8", errors="replace"))
            meta = payload.get("meta") or {}
            code = meta.get("code")
            if code not in (200, "200", None):
                raise RuntimeError("CEYE API 返回错误: {0}".format(meta))
            rows = payload.get("data") or []
            return [
                {
                    "name": str(row.get("name") or row.get("url") or ""),
                    "remote_addr": str(row.get("remote_addr") or ""),
                    "created_at": str(row.get("created_at") or ""),
                }
                for row in rows
            ]
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            time.sleep(0.8 * (attempt + 1))
    raise RuntimeError("CEYE 查询失败: {0}".format(last_error))


def _ceye_wait(config: Dict[str, str], filter_text: str, wait: float) -> List[dict]:
    deadline = time.time() + max(0.0, wait)
    records: List[dict] = []
    while True:
        try:
            found = _ceye_query(config, filter_text)
            if found:
                return found
        except Exception:  # noqa: BLE001
            pass
        if time.time() >= deadline:
            return records
        time.sleep(1.0)


def _ceye(ctx: dict, filter_text: str = "") -> dict:
    config = _ceye_config(ctx)
    if not config:
        raise ValueError("未配置 CEYE Token：请设置环境变量 CEYE_TOKEN 或在界面填写")
    extras = ctx["extras"]
    filter_id = _dns_safe_label(extras.get("dns_filter") or filter_text or "fj")
    wait = float(extras.get("dns_wait") or 0.0)
    records = _ceye_wait(config, filter_id, wait) if wait > 0 else _ceye_query(config, filter_id)
    names = " ".join((row.get("name") or "").lower() for row in records)
    hits: Dict[str, bool] = {}
    for key in (extras.get("dns_hosts") or {}):
        hits[key] = _dns_safe_label(key, 8) in names
    return {
        "mode": "ceye",
        "ceye_domain": config["domain"],
        "filter": filter_id,
        "count": len(records),
        "records": records,
        "hits": hits,
        "confirmed": bool(records),
        "summary": "CEYE 确认 {0} 条记录".format(len(records)) if records else "CEYE 暂无匹配记录",
    }
def _dns_version_domains(dnslog_host: str, label: str = "fj") -> Dict[str, str]:
    host = _strip_dns_host(dnslog_host)
    if not host:
        raise ValueError("版本 DNS 探针需要 dnslog 主机名")
    label = _dns_safe_label(label, 12) or "fj"
    return {
        "le47": "{0}le47.{1}".format(label, host),
        "le68": "{0}le68.{1}".format(label, host),
        "d80a": "{0}d80a.{1}".format(label, host),
        "d80b": "{0}d80b.{1}".format(label, host),
    }


def _flag(extras: Dict[str, Any], key: str, default: bool = False) -> bool:
    """解析开关值；未显式提供时取默认值。"""
    value = extras.get(key)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def _stage_flags(ctx: dict) -> Tuple[bool, bool]:
    """读取 DNS 探针 / CEYE 确认开关，未显式提供时默认开启。"""
    extras = ctx["extras"]
    return _flag(extras, "dns_enabled", True), _flag(extras, "ceye_enabled", True)


PROBE_METHOD_FALLBACKS = ("POST", "GET", "PUT", "PATCH")


def _probe_result_blocked(result: dict) -> bool:
    """探测结果是否说明请求在 HTTP 层被整体拒绝（方法 / 路径 / 登录拦截）。"""
    evidence = result.get("evidence") or []
    if not evidence:
        return False
    statuses = [item.get("status") for item in evidence]
    if any(status is None or status < 400 for status in statuses):
        return False
    return bool(result.get("notes")) or not any(
        (item.get("response_excerpt") or "").strip() for item in evidence
    )


VOLATILE_PATTERNS = (
    re.compile(r";?jsessionid=[0-9A-Za-z.+_-]+", re.I),
    re.compile(r"JSESSIONID=[0-9A-Za-z.+_-]+", re.I),
    re.compile(r"\b[0-9a-f]{16,}\b", re.I),
)


def _normalize_volatile(body: str) -> str:
    """抹掉每次请求都会变的标记（会话 ID 等），便于判断两份响应是否同一份模板。

    容器会在页面每个链接后重写 `;jsessionid=...`，同一张静态页逐字比对永远不相等，
    必须先归一化再比对，否则静态页会被误判成「响应随 payload 变化」。
    """
    text = body or ""
    for pattern in VOLATILE_PATTERNS:
        text = pattern.sub("<volatile>", text)
    return text


def _uniform_response_block(pairs: Sequence[Tuple[str, Optional[int], str, Optional[str]]]) -> Optional[str]:
    """判断所有探针是否拿到完全相同的响应。

    静态登录页、统一错误页会对任何请求体返回同一份内容；此时即使 HTTP 200，
    也只能说明请求没到达 JSON 解析器，不能据此判定 Fastjson。
    必须同时满足「响应体非空且唯一」与「没有任何解析器特征」才成立。
    """
    bodies = [_normalize_volatile(body).strip() for _, _, body, _ in pairs if (body or "").strip()]
    if len(pairs) < 2:
        return None
    if any(_markers(body, ALL_PARSER_MARKERS) for body in bodies):
        return None
    if not bodies:
        return None
    shortest = min(len(body) for body in bodies)
    if shortest == 0:
        return None
    shared = len(os.path.commonprefix(bodies))
    # 前缀占比高说明各探针拿到的是同一份模板（静态页 / 统一错误页）；
    # 只有尾部少量字符不同（例如每次新建的 jsessionid）不构成「响应有差异」。
    if shared < shortest * 0.9:
        return None
    return "所有探针返回同一份响应（相同前缀 {0}/{1} 字节），判断请求未到达 JSON 解析器".format(
        shared, shortest
    )


def _probe_result_varied(result: dict) -> bool:
    """探针响应是否随 payload 变化。

    全部探针返回同一份内容（例如静态登录页 / 统一错误页）说明请求没到达解析器，
    换方法后即使 HTTP 200 也没有探测价值，不能当成成功。

    只统计**真的拿到了响应**的探针：连接中断会留下 `status=None`，把它算进
    `set(statuses)` 会让集合凭空多出一个元素，于是「混了连接失败的同一份 501 错误页」
    被判成「响应随 payload 变化」，换方法逻辑就会误报成功。
    """
    bodies = [_normalize_volatile(item.get("response_excerpt") or "").strip()
              for item in (result.get("evidence") or [])]
    bodies = [body for body in bodies if body]
    if not bodies:
        return False
    statuses = {
        item.get("status")
        for item in (result.get("evidence") or [])
        if item.get("status") is not None
    }
    return len(set(bodies)) > 1 or len(statuses) > 1


def _probe_with_method_fallback(ctx: dict, runner, dnslog_host: str) -> dict:
    """先按当前方法探测；若探针被 HTTP 层整体拒绝，再依次换方法重试。

    只在「全部探针都是 4xx/5xx 且没有任何解析器特征」时才换方法，避免把
    接口自身的业务报错当成方法错误而重复投递。重试后还要求响应随 payload 变化，
    否则说明请求仍未到达解析器（例如统一登录页），不能当成成功。
    重试顺序固定为 POST/GET/PUT/PATCH，首轮方法不重复；
    每次重试都在报告中留下痕迹，便于人工判断。
    """
    first = runner(ctx)
    if not _probe_result_blocked(first):
        return first
    tried = [ctx.get("request_method") or "POST"]
    for candidate in PROBE_METHOD_FALLBACKS:
        if candidate in tried:
            continue
        tried.append(candidate)
        retry_ctx = dict(ctx)
        retry_ctx["request_method"] = candidate
        retry = runner(retry_ctx)
        reached_app = any(
            item.get("status") is not None and item.get("status") < 400
            for item in (retry.get("evidence") or [])
        )
        if reached_app:
            # 换方法后请求到达了应用层（不再是 HTTP 层整体拒绝），无论是否探测成功都停止重试
            if _probe_result_varied(retry):
                retry["request_method"] = candidate
                retry["notes"] = list(retry.get("notes") or []) + [
                    "首次用 {0} 请求被 HTTP 层整体拒绝，已自动改用 {1} 重试成功。".format(tried[0], candidate)
                ]
                retry["summary"] = "{0}（首次 {1} 被拒绝，改用 {2} ）".format(
                    retry.get("summary", ""), tried[0], candidate
                )
                return retry
            first["request_method_tried"] = tried
            first["notes"] = list(first.get("notes") or []) + [
                "首次用 {0} 被拒绝，改用 {1} 后仍只拿到静态页（未到达解析器）。".format(tried[0], candidate)
            ]
            first["summary"] = "{0}（已尝试 {1}，均未拿到解析器响应）".format(
                first.get("summary", ""), "、".join(tried)
            )
            return _annotate_login_gate(ctx, first)
        if not _probe_result_blocked(retry) and _probe_result_varied(retry):
            retry["request_method"] = candidate
            retry["notes"] = list(retry.get("notes") or []) + [
                "首次用 {0} 请求被 HTTP 层整体拒绝，已自动改用 {1} 重试成功。".format(tried[0], candidate)
            ]
            retry["summary"] = "{0}（首次 {1} 被拒绝，改用 {2} ）".format(
                retry.get("summary", ""), tried[0], candidate
            )
            return retry
    first["request_method_tried"] = tried
    first["summary"] = "{0}；已尝试请求方法：{1}".format(
        first.get("summary", ""), "、".join(tried)
    )
    return _annotate_login_gate(ctx, first)


def _attach_optional_stages(ctx: dict, result: dict, dnslog_host: str) -> dict:
    """按开关把 DNS 探针 / CEYE 确认附加到主结果。

    `dns_enabled` 控制是否投递 DNS 探针；`ceye_enabled` 控制是否调用 CEYE 接口确认记录。
    CEYE 确认依赖 DNS 探针：任一前提不满足时整体跳过，并写明原因，不影响主探测结果。
    """
    dns_enabled, ceye_enabled = _stage_flags(ctx)
    host = _strip_dns_host(dnslog_host or ctx["extras"].get("dnslog_host") or "")

    if dns_enabled and host:
        enabled = ["DNS 探针"]
        if ceye_enabled:
            enabled.append("CEYE 确认")
        dns_result = _dns(ctx, host, confirm=ceye_enabled)
        result["dns"] = dns_result
        result["dnslog_host"] = dns_result.get("dnslog_host", "")
        result["dns_filter"] = dns_result.get("dns_filter", "")
        result["dns_records"] = dns_result.get("dns_records", [])
        result["stages"] = {"dns": True, "ceye": ceye_enabled}
        result["summary"] = "{0}；附加步骤：{1}（{2}）".format(
            result.get("summary", ""), " + ".join(enabled), dns_result.get("summary", "")
        )
        return result

    result["stages"] = {"dns": False, "ceye": False}
    if not dns_enabled and ceye_enabled:
        reason = "DNS 探针已关闭，CEYE 确认依赖 DNS 探针，一并跳过"
    elif not dns_enabled:
        reason = "DNS 探针与 CEYE 确认均已关闭"
    else:
        reason = "未填写 DNSLog 主机"
    result["notes"] = list(result.get("notes") or []) + ["附加步骤已跳过：{0}".format(reason)]
    result["summary"] = "{0}；附加步骤：已跳过（{1}）".format(result.get("summary", ""), reason)
    return result


def _subject_from_extras(ctx: dict) -> Dict[str, Any]:
    """从 extras 收集抓包主题（URL / 方法 / 头 / 体 / 查询参数）。"""
    extras = ctx["extras"]
    url = str(extras.get("capture_url") or ctx["target"] or "").strip()
    method = str(extras.get("method") or "POST").strip().upper() or "POST"
    headers = dict(extras.get("request_headers") or ctx["headers"] or {})
    body = extras.get("body")
    if body is None:
        body = ""
    body = str(body)
    query = extras.get("query") or {}
    if not isinstance(query, dict):
        query = {}
    content_type = str(extras.get("content_type") or "").strip()
    if not content_type:
        content_type = _header_lookup(headers, "content-type")
    return {
        "url": url,
        "method": method,
        "headers": {str(k): str(v) for k, v in headers.items()},
        "content_type": content_type,
        "query": {str(k): str(v) for k, v in query.items()},
        "body": body,
    }


def _capture(ctx: dict) -> dict:
    """独立抓包：按指定方法发送一次请求，记录耗时与响应，并给出格式转换结果。"""
    subject = _subject_from_extras(ctx)
    url = subject["url"]
    if not url:
        raise ValueError("capture_url / target 不能为空")
    url = _normalize_target(url)
    method = subject["method"]
    headers = dict(subject["headers"])
    query = subject["query"]
    if query:
        query_text = urllib.parse.urlencode(query)
        url = url + ("&" if "?" in url else "?") + query_text
    content_type = subject["content_type"]
    if content_type and not _header_lookup(headers, "content-type"):
        headers["Content-Type"] = content_type
    body = "" if method in ("GET", "HEAD") else subject["body"]

    status, elapsed, payload, response_headers, error = _request_raw(
        method, url, body, ctx["timeout"], headers, ""
    )
    decoded = payload
    transfer_encoding = _header_lookup(response_headers, "transfer-encoding")
    if "chunked" in transfer_encoding.lower() and payload.strip():
        decoded = _decode_chunked(payload)

    cookies = _cookie_pairs(_header_lookup(response_headers, "set-cookie"))
    request_cookies = _cookie_pairs(_header_lookup(headers, "cookie"))
    notes: List[str] = []
    if status is None:
        notes.append("连接失败：请确认目标可达、端口开放，以及代理/证书设置。")
    elif 300 <= status < 400:
        location = _header_lookup(response_headers, "location")
        notes.append(
            "收到 {0} 跳转（Location: {1}）：抓包默认不跟随跳转，便于观察原始响应。".format(
                status, location or "未提供"
            )
        )
    if status is not None and not payload.strip():
        notes.append("响应体为空：该路径可能只接受其他方法，或需要登录/额外请求头。")
    if not request_cookies:
        notes.append("请求未携带 Cookie：若目标需要登录，把会话 Cookie 填入请求头或使用粘贴解析。")

    requested = ctx["extras"].get("convert_targets") or list(CONVERT_FORMATS)
    if isinstance(requested, str):
        requested = [item.strip() for item in requested.split(",") if item.strip()]
    requested = [str(item).strip() for item in requested if str(item).strip()] or list(CONVERT_FORMATS)
    forms = {target: convert_report(subject, target) for target in requested}
    request_summary = {
        "method": method,
        "url": url,
        "headers": headers,
        "content_type": content_type,
        "body": body,
    }
    response_summary = {
        "status": status,
        "elapsed_ms": elapsed,
        "headers": {str(k): str(v) for k, v in (response_headers or {}).items()},
        "body_excerpt": _excerpt(decoded),
        "body_length": len(payload or ""),
        "error": error,
    }
    summary = (
        "抓包失败：{0}".format(error)
        if error
        else "已发送 {0} 请求，响应 HTTP {1}（{2} ms，{3} 字节）".format(
            method, status, elapsed, len(payload or "")
        )
    )
    return {
        "mode": "capture",
        "target": url,
        "method": method,
        "content_type": content_type,
        "request": request_summary,
        "status": status,
        "elapsed_ms": elapsed,
        "response_headers": response_summary["headers"],
        "body": decoded,
        "body_raw": payload,
        "body_length": response_summary["body_length"],
        "request_cookies": {name: value for name, value in request_cookies},
        "response_cookies": {name: value for name, value in cookies},
        "conversions": forms,
        "summary": summary,
        "notes": notes,
        "error": error,
        "limitations": [
            "抓包只发送一次请求，不做任何变形或重复投递，请确认目标已授权。",
            "HTTP 客户端不校验 TLS 证书，也不跟随 30x 跳转。",
            "响应体只截取前 1 MB，超出部分不会展示。",
        ],
    }


def _convert(ctx: dict) -> dict:
    """报文转换：把粘贴的原始请求或 URL 转成常用格式。"""
    extras = ctx["extras"]
    pasted = str(extras.get("pasted_request") or "").strip()
    targets = extras.get("convert_targets") or list(CONVERT_FORMATS)
    if isinstance(targets, str):
        targets = [item.strip() for item in targets.split(",") if item.strip()]
    targets = [str(item).strip() for item in targets if str(item).strip()] or list(CONVERT_FORMATS)

    if pasted:
        parsed = parse_pasted_request(pasted)
        url = _absolute_url(parsed["url"] or ctx["target"], parsed["headers"])
        subject = {
            "url": url,
            "method": parsed["method"] or "POST",
            "headers": parsed["headers"],
            "content_type": _header_lookup(parsed["headers"], "content-type"),
            "query": dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query, keep_blank_values=True)),
            "body": parsed["body"],
        }
        source = "粘贴的原始请求"
    else:
        subject = _subject_from_extras(ctx)
        source = "手工填写字段"
    if not subject["url"]:
        raise ValueError("请粘贴原始请求，或至少填写 URL")

    forms = {target: convert_report(subject, target) for target in targets}
    return {
        "mode": "convert",
        "target": subject["url"],
        "source": source,
        "method": subject["method"],
        "conversions": forms,
        "request": _subject_json(subject),
        "summary": "已从{0}生成 {1} 种格式：{2}".format(source, len(forms), "、".join(forms)),
        "notes": [
            "cookie-json / cookie-header 便于直接填入探测页的请求头字段。",
            "格式转换只在本地进行，不会访问任何目标。",
        ],
        "limitations": ["文本解析是启发式的，畸形报文可能解析不全。"],
    }


def run(
    target: str,
    mode: str = "detect",
    timeout: float = DEFAULT_TIMEOUT,
    headers: Optional[Dict[str, str]] = None,
    base_body: str = "",
    dnslog_host: str = "",
    extras: Optional[Dict[str, Any]] = None,
) -> dict:
    """统一入口：mode 为 detect / version / expect / dns / ceye。"""
    extras = dict(extras or {})
    mode = (mode or "detect").strip().lower()
    # 抓包 / 转换模式允许只用 --capture-url 指定目标，不强制位置参数
    if mode in ("capture", "sniff"):
        ctx_target = _normalize_target(target or extras.get("capture_url") or "")
    elif mode in ("convert", "converter", "format"):
        # 转换模式可以完全离线：URL 只作为输出上下文，允许为空
        ctx_target = str(target or extras.get("capture_url") or "").strip()
    else:
        ctx_target = _normalize_target(target)
    session_cookie = _clean_session_cookie(extras.get("session_cookie"))
    if session_cookie:
        extras["session_cookie"] = session_cookie
        headers, _added = _merge_session_cookie(dict(headers or {}), session_cookie)
    ctx = {
        "target": ctx_target,
        "timeout": float(timeout) if timeout else DEFAULT_TIMEOUT,
        "headers": {str(k): str(v) for k, v in (headers or {}).items()},
        "content_type": str(extras.get("content_type") or DEFAULT_CONTENT_TYPE),
        "request_method": _request_method(extras),
        "extras": extras,
    }
    if ctx["timeout"] <= 0 or ctx["timeout"] > 120:
        raise ValueError("timeout 必须在 0 到 120 秒之间")

    if mode in ("capture", "sniff"):
        return _capture(ctx)
    if mode in ("convert", "converter", "format"):
        return _convert(ctx)
    if mode in ("detect", "fingerprint", "probe"):
        return _annotate_login_gate(
            ctx,
            _attach_optional_stages(
                ctx, _probe_with_method_fallback(ctx, _fingerprint, dnslog_host), dnslog_host
            ),
        )
    if mode in ("version", "ver"):
        use_dns, use_ceye = _stage_flags(ctx)
        if use_dns and dnslog_host:
            dns_hosts = _dns_version_domains(dnslog_host, extras.get("dns_label") or "fj")
        else:
            dns_hosts = None
        result = _probe_with_method_fallback(ctx, lambda item: _version(item, dns_hosts), dnslog_host)
        result["stages"] = {"dns": bool(dns_hosts), "ceye": bool(dns_hosts) and use_ceye}
        return _annotate_login_gate(ctx, result)
    if mode in ("expect", "expected", "expectclass"):
        return _annotate_login_gate(
            ctx,
            _attach_optional_stages(
                ctx,
                _probe_with_method_fallback(ctx, lambda item: _expect(item, base_body), dnslog_host),
                dnslog_host,
            ),
        )
    if mode == "dns":
        # 显式 DNS 模式同样遵守界面开关：关闭时不投递任何探针
        if not _stage_flags(ctx)[0]:
            return {
                "mode": "dns",
                "target": ctx["target"],
                "dnslog_host": "",
                "dns_filter": "",
                "dns_hits": {},
                "dns_records": [],
                "notes": ["DNS 探针开关已关闭：未投递任何 DNS 探针"],
                "summary": "DNS 探针已跳过（开关关闭）",
                "evidence": [],
                "limitations": ["开关关闭时不会访问目标，命中结果为空属于预期。"],
            }
        return _dns(ctx, dnslog_host, confirm=_stage_flags(ctx)[1])
    if mode == "ceye":
        # 显式 CEYE 模式同样遵守界面开关：关闭时不访问 CEYE 接口
        if not _stage_flags(ctx)[1]:
            return {
                "mode": "ceye",
                "confirmed": False,
                "count": 0,
                "records": [],
                "hits": {},
                "summary": "CEYE 确认已跳过（开关关闭）",
            }
        return _ceye(ctx, dnslog_host)
    raise ValueError("未知模式: {0}".format(mode))


def detect(
    target: str,
    timeout: float = DEFAULT_TIMEOUT,
    headers: Optional[Dict[str, str]] = None,
    **kwargs: Any,
) -> dict:
    """兼容旧入口：Fastjson 识别。"""
    return run(target, "detect", timeout, headers, extras=kwargs)


MODE_TITLES = {
    "detect": "Fastjson 识别",
    "version": "版本识别",
    "expect": "期望类",
    "dns": "DNS 探针",
    "ceye": "CEYE 确认",
    "capture": "HTTP 抓包",
    "convert": "报文转换",
}

STATUS_NOTES = {
    "baseline": "标准 JSON",
    "broken_json": "残缺 JSON",
    "autotype_marker": "@type 标记",
    "parse_features": "宽松语法",
    "ref_resolve": "$ref 引用",
    "diff_jackson_extra": "多余字段",
    "diff_jackson_quote": "单引号",
    "diff_gson_float": "浮点精度",
    "baseline_ok": "合法 JSON 基线",
    "negative_control": "残缺 JSON 基线",
    "autotype_class": "Class 空 val",
    "autotype_random": "随机类名",
    "safemode_string": "SafeMode 畸形串",
    "autocloseable_exact": "AutoCloseable 回显",
    "probe_1_2_83": "Test.TestException",
    "offline_exception": "Exception 混用",
    "offline_autocloseable": "AutoCloseable+BAOS",
    "offline_class_jdbc": "Class+JdbcRowSet",
    "offline_jdbc": "JdbcRowSetImpl",
    "feature_type": "Feature 注入",
    "empty_key_root": "根级空键",
    "empty_key_nested": "嵌套空键对照",
    "capture_request": "抓包请求",
}


def _one_line(text: str, limit: int = 220) -> str:
    collapsed = " ".join((text or "").split())
    if not collapsed:
        return "（空响应）"
    return collapsed if len(collapsed) <= limit else collapsed[: limit - 3] + "..."


def _status_text(status: Optional[int], error: Optional[str]) -> str:
    if status is None:
        return "连接失败"
    return "HTTP {0}".format(status)


def _render_evidence(items: Sequence[dict], limit: int = 12) -> List[str]:
    """渲染探针明细。

    默认只给出「每条探针是什么、什么状态、命中哪些特征」的结论表，响应正文与错误
    只在**确实有内容**时附带一行摘要。原因是绝大多数探针的响应体是空的（405、403、
    统一错误页等），过去那种「每条探针固定打印一行`响应: （空响应）`」会让结果区
    大半篇幅都在重复同一句话，反而看不到真正的结论。
    """
    lines: List[str] = []
    shown = items[:limit]
    for index, item in enumerate(shown, start=1):
        probe_id = item.get("probe_id", "")
        note = STATUS_NOTES.get(probe_id, item.get("description", ""))
        matched = item.get("matched") or []
        hits = "、".join(matched) if matched else "无"
        lines.append(
            "  {0:>2}. {1:<16} {2:<10} 命中特征: {3}".format(
                index, note, _status_text(item.get("status"), item.get("error")), hits
            )
        )
        excerpt = _one_line(item.get("response_excerpt"))
        if excerpt and excerpt != "（空响应）":
            lines.append("      响应: {0}".format(excerpt))
        if item.get("error"):
            lines.append("      错误: {0}".format(_one_line(item.get("error"))))
    omitted = len(items) - len(shown)
    if omitted > 0:
        lines.append("  ... 其余 {0} 条探针已省略（均为无特征响应）".format(omitted))
    return lines


def _render_detect(result: dict) -> List[str]:
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("探测方法: {0}".format(result.get("request_method") or "POST"))
    lines.append(
        "是否 Fastjson: {0}    置信度: {1}".format(
            "是" if result.get("is_fastjson") else "否", result.get("confidence")
        )
    )
    scores = result.get("scores") or {}
    lines.append(
        "指纹得分: " + "  ".join("{0}={1}".format(k, v) for k, v in scores.items())
    )
    lines.append("")
    lines.append("探针明细:")
    lines.extend(_render_evidence(result.get("evidence") or []))
    return lines


def _render_version(result: dict) -> List[str]:
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("探测方法: {0}".format(result.get("request_method") or "POST"))
    lines.append("回显版本: {0}".format(result.get("reported_version") or "未回显"))
    lines.append("布尔探针区间: {0}".format(result.get("version_detail") or "未能收敛"))
    lines.append("PoC 档位: {0}".format(result.get("version_range") or "未能收敛"))
    lines.append("置信度: {0}".format(result.get("confidence")))
    autotype = result.get("autotype_enabled")
    autotype_text = "未判定" if autotype is None else ("开启" if autotype else "关闭")
    safemode = result.get("safemode_enabled")
    safemode_text = "未判定" if safemode is None else ("已启用" if safemode else "未启用")
    lines.append("AutoType: {0}    SafeMode: {1}".format(autotype_text, safemode_text))
    lines.append("")
    lines.append("探针明细:")
    lines.extend(_render_evidence(result.get("evidence") or []))
    return lines


def _render_expect(result: dict) -> List[str]:
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("探测方法: {0}".format(result.get("request_method") or "POST"))
    has_expect = result.get("has_expect_class")
    lines.append(
        "是否存在期望类: {0}".format("未判定" if has_expect is None else ("存在" if has_expect else "不存在"))
    )
    not_map = result.get("expect_not_map")
    lines.append(
        "期望类型非 Map: {0}".format("未判定" if not_map is None else ("是" if not_map else "否"))
    )
    lines.append("置信度: {0}".format(result.get("confidence")))
    lines.append("")
    lines.append("探针明细:")
    lines.extend(_render_evidence(result.get("evidence") or []))
    return lines


def _render_dns(result: dict) -> List[str]:
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("DNSLog 主机: {0}".format(result.get("dnslog_host") or "未填写"))
    lines.append("filter: {0}".format(result.get("dns_filter") or "-"))
    hits = result.get("dns_hits") or {}
    lines.append("命中: {0}".format("、".join(k for k, v in hits.items() if v) or "无"))
    lines.append("")
    lines.append("探针明细:")
    lines.extend(_render_evidence(result.get("evidence") or []))
    return lines


def _render_ceye(result: dict) -> List[str]:
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("确认命中: {0}".format("是" if result.get("confirmed") else "否"))
    lines.append("记录条数: {0}".format(result.get("count", 0)))
    for record in (result.get("records") or [])[:10]:
        lines.append(
            "  - {0}  {1}  {2}".format(
                record.get("name", ""), record.get("remote_addr", ""), record.get("created_at", "")
            )
        )
    return lines


def _render_capture(result: dict) -> List[str]:
    request = result.get("request") or {}
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("请求方法: {0}".format(result.get("method", "")))
    lines.append("请求 URL : {0}".format(request.get("url", "")))
    if request.get("content_type"):
        lines.append("Content-Type: {0}".format(request["content_type"]))
    request_headers = request.get("headers") or {}
    if request_headers:
        lines.append("请求头:")
        for name, value in request_headers.items():
            lines.append("  {0}: {1}".format(name, _one_line(str(value), 160)))
    if request.get("body"):
        lines.append("请求体: {0}".format(_one_line(request["body"])))
    lines.append("")
    lines.append("响应状态: {0}".format(_status_text(result.get("status"), result.get("error"))))
    lines.append("响应耗时: {0} ms".format(result.get("elapsed_ms")))
    lines.append("响应字节: {0}".format(result.get("body_length", 0)))
    response_headers = result.get("response_headers") or {}
    if response_headers:
        lines.append("响应头:")
        for name, value in response_headers.items():
            lines.append("  {0}: {1}".format(name, _one_line(str(value), 160)))
    lines.append("")
    lines.append("响应体:")
    lines.append("  {0}".format(_one_line(result.get("body") or "（空响应）", 600)))
    response_cookies = result.get("response_cookies") or {}
    if response_cookies:
        lines.append("")
        lines.append("响应 Set-Cookie:")
        for name, value in response_cookies.items():
            lines.append("  {0} = {1}".format(name, value))
    lines.append("")
    lines.append("格式转换:")
    for name, text in (result.get("conversions") or {}).items():
        lines.append("  --- {0} ---".format(name))
        for row in (text or "").splitlines():
            lines.append("      {0}".format(row))
    return lines


def _render_convert(result: dict) -> List[str]:
    lines = ["探测结论: {0}".format(result.get("summary", ""))]
    lines.append("来源: {0}".format(result.get("source", "")))
    lines.append("请求方法: {0}".format(result.get("method", "")))
    lines.append("请求 URL : {0}".format(result.get("target", "")))
    lines.append("")
    lines.append("格式转换:")
    for name, text in (result.get("conversions") or {}).items():
        lines.append("  --- {0} ---".format(name))
        for row in (text or "").splitlines():
            lines.append("      {0}".format(row))
    return lines


RENDERERS = {
    "detect": _render_detect,
    "version": _render_version,
    "expect": _render_expect,
    "dns": _render_dns,
    "ceye": _render_ceye,
    "capture": _render_capture,
    "convert": _render_convert,
}


def _split_conclusion(text: str) -> Tuple[str, str]:
    """把「结论 + 原因 + 建议」拼成的长句拆成两段。

    引擎为了兼顾 JSON 调用方，把结论、原因与建议写在同一个 summary 里；直接铺在
    「探测结论:」那一行会让单行超长、难以扫描。这里按第一个分句边界拆开，
    结论留在原位，原因与建议另起一段，JSON 中的 summary 字段不受影响。
    """
    body = (text or "").strip()
    if not body:
        return "", ""
    for separator in ("。", "；"):
        index = body.find(separator)
        # 太靠前说明只是个短前缀（如「无法探测：」），继续往后找更完整的分句
        if 8 <= index <= 120:
            return body[: index + 1], body[index + 1 :].strip()
    return body, ""


def _wrap_conclusion(lines: Sequence[str]) -> List[str]:
    """把渲染结果里「探测结论:」那一行拆成结论 + 原因/建议两段。"""
    if not lines or not lines[0].startswith("探测结论:"):
        return list(lines)
    head, tail = _split_conclusion(lines[0][len("探测结论:") :])
    wrapped = ["探测结论: {0}".format(head)]
    if tail:
        wrapped.append("          {0}".format(tail))
    wrapped.extend(lines[1:])
    return wrapped


def _strip_label(text: str) -> str:
    """剥掉提示开头的短标签，只留下正文。

    同一个原因在 `notes` 与 `summary` 里往往挂着不同的标签——notes 侧是
    「探测未生效：所有探针均返回 HTTP 405…」，summary 侧是
    「无法探测：所有探针均返回 HTTP 405…」。只按前 12 字比对会把标签算进去，
    于是同一条原因在报告里被打印两次。剥掉 `Xxx：` 之后正文才能对齐。
    """
    body = (text or "").strip()
    for index in (body.find("："), body.find(":")):
        # 标签本身要短，否则会把正文里的冒号当成标签边界
        if 1 <= index <= 10:
            return body[index + 1 :].strip()
    return body


def _dedupe_notes(notes: Sequence[str], summary: str) -> List[str]:
    """去掉与探测结论重复的提示。

    跳过 DNS / CEYE 阶段时，引擎会把同一条原因分别写进 summary 与 notes（JSON 里
    两处都保留，便于程序化消费）；报告里再把它们原样打印一遍，使用者会连续读到
    两段完全相同的文字。这里只对**渲染结果**去重，不改变 notes 字段本身。
    """
    kept: List[str] = []
    head = _strip_label(summary or "")
    for note in notes:
        text_note = (note or "").strip()
        if not text_note:
            continue
        # 提示尾部往往比结论里的更完整（例如多一个句号），取前 12 字比对即可
        probe = _strip_label(text_note)[:12]
        if probe and probe in head:
            continue
        kept.append(text_note)
    return kept


def format_report(result: dict, mode: str = "") -> str:
    """把结果 JSON 渲染成便于人工阅读的中文报告（界面默认使用）。"""
    if result.get("error"):
        return "探测失败: {0}".format(result["error"])
    key = (mode or result.get("mode") or "detect").strip().lower()
    renderer = RENDERERS.get(key, _render_detect)
    title = MODE_TITLES.get(key, key)
    lines = ["===== {0} =====".format(title), "目标: {0}".format(result.get("target", ""))]
    lines.append("")
    lines.extend(_wrap_conclusion(renderer(result)))
    notes = _dedupe_notes(result.get("notes") or [], result.get("summary", ""))
    if notes:
        lines.append("")
        lines.append("提示:")
        lines.extend("  - {0}".format(note) for note in notes)
    stages = result.get("stages")
    if isinstance(stages, dict):
        lines.append("")
        lines.append(
            "阶段: DNS 探针 {0} / CEYE 确认 {1}".format(
                "已执行" if stages.get("dns") else "未执行",
                "已执行" if stages.get("ceye") else "未执行",
            )
        )
    limitations = result.get("limitations") or []
    if limitations:
        lines.append("")
        lines.append("已知限制:")
        lines.extend("  - {0}".format(item) for item in limitations)
    return "\n".join(lines)


def _build_parser() -> argparse.ArgumentParser:
    """构造命令行解析器；界面与 CLI 共用同一套开关语义。"""
    parser = argparse.ArgumentParser(description="Fastjson 识别 / 版本 / 期望类 / DNS 探针")
    parser.add_argument("target", nargs="?", default="", help="已授权的 HTTP JSON 端点")
    parser.add_argument("--mode", default="detect", choices=sorted(MODE_LABELS))
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--headers", default="{}", help='JSON 对象，例如 {"Authorization":"Bearer x"}')
    parser.add_argument("--base-body", default="", help="期望类 / 版本对照的业务参数")
    parser.add_argument("--dnslog-host", default="", help="DNSLog / CEYE 域名，例如 abc.ceye.io")
    parser.add_argument("--dns-filter", default="", help="CEYE filter，最长 20 字符")
    parser.add_argument("--dns-wait", type=float, default=DEFAULT_DNS_WAIT, help="等待 DNS 记录秒数")
    parser.add_argument("--ceye-token", default="", help="CEYE Token（或环境变量 CEYE_TOKEN）")
    parser.add_argument("--ceye-domain", default="", help="CEYE 主域名")
    parser.add_argument("--content-type", default=DEFAULT_CONTENT_TYPE)
    parser.add_argument(
        "--session-cookie",
        default="",
        help="已登录会话 Cookie，形如 JWT_TOKEN=x; JSESSIONID=y（目标有登录拦截时必填）",
    )
    parser.add_argument(
        "--method",
        default="POST",
        choices=HTTP_METHODS,
        help="抓包使用的 HTTP 方法（仅 capture 模式生效）",
    )
    parser.add_argument("--body", default="", help="抓包请求体（仅 capture 模式生效）")
    parser.add_argument("--capture-url", default="", help="抓包目标 URL，缺省回落到 target")
    parser.add_argument(
        "--probe-method",
        default="",
        help="探测请求方法（默认 POST）：{0}".format("/".join(SUPPORTED_REQUEST_METHODS)),
    )
    parser.add_argument("--pasted-request", default="", help="粘贴的原始 HTTP 请求文本（convert 模式）")
    parser.add_argument(
        "--convert-targets",
        default="",
        help="转换目标，逗号分隔：{0}".format(",".join(CONVERT_FORMATS)),
    )
    parser.add_argument(
        "--query",
        default="{}",
        help='查询参数 JSON 对象，例如 {"id":"1"}（capture 模式）',
    )
    parser.add_argument(
        "--format",
        default="json",
        choices=("json", "text"),
        help="输出格式：json 供程序读取，text 为人工阅读报告",
    )
    parser.add_argument(
        "--dns",
        dest="dns_enabled",
        action="store_true",
        help="在探测流程中启用 DNS 探针（默认启用）",
    )
    parser.add_argument(
        "--ceye",
        dest="ceye_enabled",
        action="store_true",
        help="启用 CEYE 确认 DNS 记录（默认启用）",
    )
    parser.add_argument("--no-dns", dest="dns_enabled", action="store_false")
    parser.add_argument("--no-ceye", dest="ceye_enabled", action="store_false")
    parser.set_defaults(dns_enabled=True, ceye_enabled=True)
    return parser


def _main() -> int:
    parser = _build_parser()
    args = parser.parse_args()

    try:
        raw_headers = json.loads(args.headers or "{}")
        if not isinstance(raw_headers, dict):
            raise ValueError("headers 必须是 JSON 对象")
        raw_query = json.loads(args.query or "{}")
        if not isinstance(raw_query, dict):
            raise ValueError("query 必须是 JSON 对象")
        extras = {
            "content_type": args.content_type,
            "dns_filter": args.dns_filter,
            "dns_wait": args.dns_wait,
            "ceye_token": args.ceye_token,
            "ceye_domain": args.ceye_domain,
            "dnslog_host": args.dnslog_host,
            "dns_enabled": args.dns_enabled,
            "ceye_enabled": args.ceye_enabled,
            "method": args.method,
            "probe_method": args.probe_method or args.method,
            "body": args.body,
            "capture_url": args.capture_url,
            "pasted_request": args.pasted_request,
            "convert_targets": [item.strip() for item in (args.convert_targets or "").split(",") if item.strip()],
            "query": {str(k): str(v) for k, v in raw_query.items()},
            "request_headers": raw_headers,
            "session_cookie": args.session_cookie,
        }
        result = run(
            args.target,
            args.mode,
            args.timeout,
            raw_headers,
            base_body=args.base_body,
            dnslog_host=args.dnslog_host,
            extras=extras,
        )
        if args.format == "text":
            print(format_report(result, args.mode))
        else:
            print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"error": "{0}: {1}".format(type(exc).__name__, exc)}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(_main())
