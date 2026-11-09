# -*- coding: utf-8 -*-
"""Dev-A v2 SOCKS5 test araci (sadece stdlib, Python 3.8+).

PC tarafindan Dev-A v2 (telefonda 0.0.0.0:8000 dinler) proxy'sini dogrular:

  1) TCP CONNECT       : example.com:80 ustunden HTTP HEAD akisi
  2) DNS over TCP      : 8.8.8.8:53 DNS sorgusu SOCKS5 CONNECT ile (dev_b.py --dns tcp:// yolu)
  3) UDP ASSOCIATE     : 8.8.8.8:53 DNS sorgusu UDP ile (adb arkasinda UDP gecmedigi
                         icin bu test yalnizca WiFi/dogrudan IP erisiminde PASS olur)

Kullanim:
  python test_socks.py --host 127.0.0.1 --port 1080      # adb kopru (dev_b)
  python test_socks.py --host 192.168.1.42 --port 8000   # telefona dogrudan WiFi

Sonuc satirlari PASS/FAIL; herhangi biri FAIL ise cikis kodu 1 olur.
"""
import argparse
import socket
import struct
import sys

DNS_QUERY = bytes.fromhex("123401000001000000000000") + b"\x06google\x03com\x00" + b"\x00\x01\x00\x01"
DNS_QUERY_ID = 0x1234


def recv_exact(sock: socket.socket, count: int) -> bytes:
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise ConnectionError("baglanti erken kapandi (%d/%d byte)" % (len(data), count))
        data += chunk
    return data


def handshake(sock: socket.socket) -> None:
    sock.sendall(bytes([5, 1, 0]))  # VER=5, NMETHODS=1, METHODS=[NO-AUTH]
    reply = recv_exact(sock, 2)
    if reply[0] != 5:
        raise RuntimeError("VER degil: %s" % reply.hex())
    if reply[1] != 0:
        raise RuntimeError("NO-AUTH kabul edilmedi: %s" % reply.hex())


def encode_target(host: str, port: int) -> bytes:
    """DST.ADDR + DST.PORT (atyp 1/3/4 otomatik)."""
    port_bytes = struct.pack(">H", port)
    try:
        return b"\x01" + socket.inet_aton(host) + port_bytes
    except OSError:
        pass
    if ":" in host:
        return b"\x04" + socket.inet_pton(socket.AF_INET6, host) + port_bytes
    domain = host.encode("idna")
    return b"\x03" + bytes([len(domain)]) + domain + port_bytes


def read_reply(sock: socket.socket) -> tuple[int, str, int]:
    """VER|REP|RSV|ATYP|BND|PORT okur -> (REP, bind_host, bind_port)."""
    head = recv_exact(sock, 4)
    rep = head[1]
    atyp = head[3]
    if atyp == 1:
        raw = recv_exact(sock, 6)
        host = socket.inet_ntoa(raw[:4])
        port = struct.unpack(">H", raw[4:6])[0]
    elif atyp == 3:
        dlen = recv_exact(sock, 1)[0]
        raw = recv_exact(sock, dlen + 2)
        host = raw[:dlen].decode("ascii", "replace")
        port = struct.unpack(">H", raw[-2:])[0]
    elif atyp == 4:
        raw = recv_exact(sock, 18)
        host = socket.inet_ntop(socket.AF_INET6, raw[:16])
        port = struct.unpack(">H", raw[16:18])[0]
    else:
        raise RuntimeError("BND ATYP bilinmiyor: %d" % atyp)
    return rep, host, port


def test_connect(control_host: str, control_port: int, target: str = "example.com", port: int = 80) -> bool:
    print("[test] TCP CONNECT -> %s:%d" % (target, port))
    try:
        with socket.create_connection((control_host, control_port), timeout=10) as sock:
            sock.settimeout(10)
            handshake(sock)
            sock.sendall(bytes([5, 1, 0]) + encode_target(target, port))
            rep, bhost, bport = read_reply(sock)
            if rep != 0:
                print("  FAIL: CONNECT reddedildi (REP=%d)" % rep)
                return False
            sock.sendall(
                b"HEAD / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
            )
            data = b""
            while len(data) < 64:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                data += chunk
            if b"HTTP/1." in data:
                print("  PASS (HTTP yanit geldi; BND=%s:%d)" % (bhost, bport))
                return True
            print("  FAIL: veri akisi yok")
            return False
    except Exception as exc:  # noqa: BLE001
        print("  FAIL: %s" % exc)
        return False


def test_dns_over_tcp(control_host: str, control_port: int) -> bool:
    print("[test] DNS over TCP CONNECT -> 8.8.8.8:53")
    try:
        with socket.create_connection((control_host, control_port), timeout=10) as sock:
            sock.settimeout(10)
            handshake(sock)
            sock.sendall(bytes([5, 1, 0]) + encode_target("8.8.8.8", 53))
            rep, _, _ = read_reply(sock)
            if rep != 0:
                print("  FAIL: CONNECT reddedildi (REP=%d)" % rep)
                return False
            sock.sendall(struct.pack(">H", len(DNS_QUERY)) + DNS_QUERY)
            size = struct.unpack(">H", recv_exact(sock, 2))[0]
            payload = recv_exact(sock, size)
            if len(payload) >= 4 and struct.unpack(">H", payload[:2])[0] == DNS_QUERY_ID:
                rcode = payload[3] & 0x0F
                ip = extract_answer_ipv4(payload)
                print("  PASS (rcode=%d, buldugu ip=%s)" % (rcode, ip or "yok"))
                return rcode == 0
            print("  FAIL: DNS yanit eslesmedi")
            return False
    except Exception as exc:  # noqa: BLE001
        print("  FAIL: %s" % exc)
        return False


def extract_answer_ipv4(payload: bytes) -> str:
    idx = 0
    qd, an = struct.unpack(">HH", payload[4:8])
    idx = 12
    for _ in range(qd):
        while idx < len(payload) and payload[idx] != 0:
            idx += payload[idx] + 1
        idx += 5
    for _ in range(an):
        name_ptr = struct.unpack(">H", payload[idx:idx + 2])[0]
        idx += 2
        if name_ptr & 0xC000 != 0xC000:
            while idx < len(payload) and payload[idx] != 0:
                idx += payload[idx] + 1
            idx += 1
        rtype, _, _, rdlen = struct.unpack(">HHIH", payload[idx:idx + 10])
        idx += 10
        if rtype == 1 and rdlen == 4:
            return socket.inet_ntoa(payload[idx:idx + 4])
        idx += rdlen
    return ""


def test_udp_associate(control_host: str, control_port: int, udp_host: str | None) -> bool:
    print("[test] UDP ASSOCIATE -> 8.8.8.8:53 DNS")
    try:
        with socket.create_connection((control_host, control_port), timeout=10) as sock:
            sock.settimeout(10)
            handshake(sock)
            sock.sendall(bytes([5, 3, 0]) + encode_target("0.0.0.0", 0))
            rep, bhost, bport = read_reply(sock)
            if rep != 0:
                print("  FAIL: ASSOCIATE reddedildi (REP=%d)" % rep)
                return False
            reach = udp_host or bhost
            udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp_sock.settimeout(5)
            try:
                hdr = b"\x00\x00\x00" + encode_target("8.8.8.8", 53)
                udp_sock.sendto(hdr + DNS_QUERY, (reach, bport))
                data, _ = udp_sock.recvfrom(65535)
                # SOCKS5 UDP basligini atla: RSV(2)+FRAG(1)+ATYP(1)+ADDR+PORT
                atyp = data[3]
                if atyp == 1:
                    offset = 10
                elif atyp == 3:
                    offset = 7 + data[4]
                elif atyp == 4:
                    offset = 22
                else:
                    raise RuntimeError("UDP yanit ATYP bilinmiyor: %d" % atyp)
                dns = data[offset:]
                if len(dns) >= 4 and struct.unpack(">H", dns[:2])[0] == DNS_QUERY_ID:
                    rcode = dns[3] & 0x0F
                    print("  PASS (BND=%s:%d, rcode=%d)" % (reach, bport, rcode))
                    return rcode == 0
                print("  FAIL: UDP yanit eslesmedi")
                return False
            finally:
                udp_sock.close()
    except Exception as exc:  # noqa: BLE001
        hint = ""
        if isinstance(exc, (TimeoutError, socket.timeout)) and control_host == "127.0.0.1":
            hint = " (adb sadece TCP forward eder; UDP testi icin WiFi/dogrudan IP gerekir)"
        print("  FAIL: %s%s" % (exc, hint))
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Dev-A v2 SOCKS5 test araci")
    parser.add_argument("--host", default="127.0.0.1", help="SOCKS5 kontrol adresi (alerat: adb koprusu icin 127.0.0.1)")
    parser.add_argument("--port", type=int, default=1080, help="SOCKS5 kontrol portu (adb: 1080, dogrudan: 8000)")
    parser.add_argument("--udp-host", default=None, help="UDP relay paketlerinin gonderilecegi adres (bos: BND.ADDR kullanilir)")
    parser.add_argument("--skip-udp", action="store_true", help="UDP ASSOCIATE testini atla (adb ustunden anlamsizdir)")
    args = parser.parse_args()

    results = []
    results.append(test_connect(args.host, args.port))
    results.append(test_dns_over_tcp(args.host, args.port))
    if not args.skip_udp:
        results.append(test_udp_associate(args.host, args.port, args.udp_host))

    passed = sum(1 for r in results if r)
    print("")
    print("SONUC: %d/%d test PASS" % (passed, len(results)))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
