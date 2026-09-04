from mitmproxy import ctx, http


def request(flow: http.HTTPFlow) -> None:
    # Type access proves the normal addon API is importable. No traffic is needed
    # for the self-test itself.
    _ = flow.request


def running() -> None:
    print("AUTOCRACK_MITMPROXY_ADDON_API_OK", flush=True)
    ctx.master.shutdown()
