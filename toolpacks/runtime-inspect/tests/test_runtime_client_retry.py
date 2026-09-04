#!/usr/bin/env python3
import importlib.util
from pathlib import Path
from types import SimpleNamespace

CLIENT = Path(__file__).resolve().parents[1] / "libexec" / "autocrack_runtime_client.py"
spec = importlib.util.spec_from_file_location("runtime_client_under_test", CLIENT)
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


def count_submits(kind):
    calls = []

    def fake_provider(method, request=None, timeout=15):
        calls.append((method, request, timeout))
        if method == "runtime_submit":
            return {"ok": True, "request_id": f"req-{len(calls)}"}
        raise AssertionError(f"unexpected provider call: {method}")

    original_provider = client.provider_call
    original_sleep = client.time.sleep
    original_thaw = client.best_effort_target_thaw
    client.provider_call = fake_provider
    client.time.sleep = lambda _: None
    client.best_effort_target_thaw = lambda _: False
    try:
        try:
            client.runtime_request({"kind": kind, "package": "com.example"}, timeout=0.01)
        except client.CliError as error:
            assert error.code == "RUNTIME_TIMEOUT", error.code
        else:
            raise AssertionError("runtime_request unexpectedly succeeded")
    finally:
        client.provider_call = original_provider
        client.time.sleep = original_sleep
        client.best_effort_target_thaw = original_thaw
    return sum(1 for method, _, _ in calls if method == "runtime_submit")


def count_submit_transport_retries(kind):
    calls = []
    first = True

    def fake_provider(method, request=None, timeout=15):
        nonlocal first
        calls.append((method, request, timeout))
        if method != "runtime_submit":
            raise AssertionError(f"unexpected provider call: {method}")
        if first:
            first = False
            raise client.CliError("RUNTIME_TRANSPORT_TIMEOUT", "synthetic submit timeout")
        return {"ok": True, "request_id": "req-retry"}

    original_provider = client.provider_call
    original_sleep = client.time.sleep
    original_thaw = client.best_effort_target_thaw
    client.provider_call = fake_provider
    client.time.sleep = lambda _: None
    client.best_effort_target_thaw = lambda _: False
    try:
        try:
            client.runtime_request({"kind": kind, "package": "com.example"}, timeout=0.01)
        except client.CliError as error:
            assert error.code in {"RUNTIME_TIMEOUT", "RUNTIME_TRANSPORT_TIMEOUT"}, error.code
        else:
            raise AssertionError("runtime_request unexpectedly succeeded")
    finally:
        client.provider_call = original_provider
        client.time.sleep = original_sleep
        client.best_effort_target_thaw = original_thaw
    return sum(1 for method, _, _ in calls if method == "runtime_submit")


def test_greeze_thaw_command():
    commands = []

    def fake_android_shell():
        return ["android-shell"]

    def fake_run(command, **kwargs):
        commands.append(command)
        if "pm" in command:
            return SimpleNamespace(returncode=0, stdout="package:com.example uid:12345\n", stderr="")
        if "greezer" in command:
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        raise AssertionError(command)

    original_shell = client.android_shell
    original_run = client.subprocess.run
    original_support = client._GREEZE_SUPPORTED
    original_cache = dict(client._TARGET_UID_CACHE)
    client.android_shell = fake_android_shell
    client.subprocess.run = fake_run
    client._GREEZE_SUPPORTED = None
    client._TARGET_UID_CACHE.clear()
    try:
        assert client.best_effort_target_thaw("com.example") is True
        assert any(command[-5:] == ["cmd", "greezer", "thuid", "12345", "1000"] for command in commands), commands
    finally:
        client.android_shell = original_shell
        client.subprocess.run = original_run
        client._GREEZE_SUPPORTED = original_support
        client._TARGET_UID_CACHE.clear()
        client._TARGET_UID_CACHE.update(original_cache)


def test_runtime_request_thaws_mutations_and_reads():
    for kind in ("runtime.process", "control.process.kill"):
        thaws = []
        calls = []

        def fake_provider(method, request=None, timeout=15):
            calls.append(method)
            if method == "runtime_submit":
                return {"ok": True, "request_id": "req"}
            raise AssertionError(method)

        original_provider = client.provider_call
        original_sleep = client.time.sleep
        original_thaw = client.best_effort_target_thaw
        client.provider_call = fake_provider
        client.time.sleep = lambda _: None
        client.best_effort_target_thaw = lambda package: thaws.append(package) or True
        try:
            try:
                client.runtime_request({"kind": kind, "package": "com.example"}, timeout=0.01)
            except client.CliError as error:
                assert error.code == "RUNTIME_TIMEOUT", error.code
            else:
                raise AssertionError("runtime_request unexpectedly succeeded")
        finally:
            client.provider_call = original_provider
            client.time.sleep = original_sleep
            client.best_effort_target_thaw = original_thaw
        assert thaws and thaws[0] == "com.example", (kind, thaws)
        assert calls.count("runtime_submit") == 1, (kind, calls)


def main():
    # A completed provider submit creates exactly one request UUID regardless of kind.
    assert count_submits("runtime.process") == 1
    assert count_submits("ui.windows") == 1
    assert count_submits("control.process.kill") == 1
    assert count_submits("ui.action") == 1

    # Only an idempotent/read-only request may retry an ambiguous submit transport failure.
    assert count_submit_transport_retries("runtime.process") == 2
    assert count_submit_transport_retries("ui.windows") == 2
    assert count_submit_transport_retries("control.process.kill") == 1
    assert count_submit_transport_retries("ui.action") == 1

    test_greeze_thaw_command()
    test_runtime_request_thaws_mutations_and_reads()
    print("RUNTIME_CLIENT_RETRY_POLICY_OK")


if __name__ == "__main__":
    main()
