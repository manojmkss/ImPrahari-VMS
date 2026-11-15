"""A fake ONVIF PTZ camera on localhost: real SOAP over HTTP, so tests drive
the real ONVIF client (zeep) end to end - request building and reply parsing
both - without a camera.

Only what services/ptz.py uses: GetCapabilities, GetProfiles, GetNode,
ContinuousMove, Stop, Get/Set/Goto/RemovePreset, Goto/SetHomePosition.
"""

import re
import threading
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NS = ('xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
      'xmlns:tt="http://www.onvif.org/ver10/schema" '
      'xmlns:tds="http://www.onvif.org/ver10/device/wsdl" '
      'xmlns:trt="http://www.onvif.org/ver10/media/wsdl" '
      'xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"')


def _envelope(body: str) -> str:
    return f'<?xml version="1.0" encoding="UTF-8"?><s:Envelope {NS}><s:Body>{body}</s:Body></s:Envelope>'


def _fault(text: str) -> str:
    return _envelope('<s:Fault><s:Code><s:Value>s:Sender</s:Value></s:Code>'
                     f'<s:Reason><s:Text xml:lang="en">{text}</s:Text></s:Reason></s:Fault>')


class FakePtzCamera:
    def __init__(self, *, ptz: bool = True, pan_tilt: bool = True, zoom: bool = True, max_presets: int = 8,
                 home: bool = True, reject_timeout: bool = False, password: str = "secret",
                 hang: set[str] | None = None, bind: tuple[str, int] = ("127.0.0.1", 0),
                 advertise: str | None = None):
        self.ptz, self.pan_tilt, self.zoom = ptz, pan_tilt, zoom
        self.max_presets, self.home, self.reject_timeout = max_presets, home, reject_timeout
        self.password = password
        self.hang = hang or set()  # actions that never answer
        self.calls: list[tuple[str, ET.Element]] = []
        self.presets: dict[str, str] = {"1": "Gate"}
        self._next = 2
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer(bind, self._handler())
        self.port = self.server.server_address[1]
        # The host the camera tells clients to use for its services.
        self.advertise = advertise or f"127.0.0.1:{self.port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    def actions(self) -> list[str]:
        with self.lock:
            return [a for a, _ in self.calls]

    def last(self, action: str) -> ET.Element:
        with self.lock:
            return [b for a, b in self.calls if a == action][-1]

    # ── replies ──────────────────────────────────────────────────────────

    def reply(self, action: str, body: ET.Element) -> tuple[int, str]:
        base = f"http://{self.advertise}/onvif"
        if action == "GetCapabilities":
            ptz = f"<tt:PTZ><tt:XAddr>{base}/ptz</tt:XAddr></tt:PTZ>" if self.ptz else ""
            return 200, _envelope(
                "<tds:GetCapabilitiesResponse><tds:Capabilities>"
                f"<tt:Device><tt:XAddr>{base}/device_service</tt:XAddr></tt:Device>"
                f"<tt:Media><tt:XAddr>{base}/media</tt:XAddr></tt:Media>{ptz}"
                "</tds:Capabilities></tds:GetCapabilitiesResponse>")
        if action == "GetProfiles":
            ptz = ""
            if self.ptz:
                ptz = ('<tt:PTZConfiguration token="ptzc"><tt:Name>ptz</tt:Name><tt:UseCount>1</tt:UseCount>'
                       "<tt:NodeToken>node1</tt:NodeToken></tt:PTZConfiguration>")
            return 200, _envelope(
                '<trt:GetProfilesResponse><trt:Profiles token="sub" fixed="true"><tt:Name>sub</tt:Name></trt:Profiles>'
                f'<trt:Profiles token="main" fixed="true"><tt:Name>main</tt:Name>{ptz}</trt:Profiles>'
                "</trt:GetProfilesResponse>")
        if action == "GetNode":
            pt = ("<tt:ContinuousPanTiltVelocitySpace><tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/"
                  "VelocityGenericSpace</tt:URI><tt:XRange><tt:Min>-1</tt:Min><tt:Max>1</tt:Max></tt:XRange>"
                  "<tt:YRange><tt:Min>-1</tt:Min><tt:Max>1</tt:Max></tt:YRange></tt:ContinuousPanTiltVelocitySpace>"
                  if self.pan_tilt else "")
            zm = ("<tt:ContinuousZoomVelocitySpace><tt:URI>http://www.onvif.org/ver10/tptz/ZoomSpaces/"
                  "VelocityGenericSpace</tt:URI><tt:XRange><tt:Min>-1</tt:Min><tt:Max>1</tt:Max></tt:XRange>"
                  "</tt:ContinuousZoomVelocitySpace>" if self.zoom else "")
            return 200, _envelope(
                '<tptz:GetNodeResponse><tptz:PTZNode token="node1" FixedHomePosition="false">'
                f"<tt:Name>node</tt:Name><tt:SupportedPTZSpaces>{pt}{zm}</tt:SupportedPTZSpaces>"
                f"<tt:MaximumNumberOfPresets>{self.max_presets}</tt:MaximumNumberOfPresets>"
                f"<tt:HomeSupported>{'true' if self.home else 'false'}</tt:HomeSupported>"
                "</tptz:PTZNode></tptz:GetNodeResponse>")
        if action == "ContinuousMove":
            if self.reject_timeout and _find(body, "Timeout") is not None:
                return 500, _fault("Timeout is not supported")
            return 200, _envelope("<tptz:ContinuousMoveResponse/>")
        if action == "GetPresets":
            with self.lock:
                items = "".join(f'<tptz:Preset token="{t}"><tt:Name>{n}</tt:Name></tptz:Preset>'
                                for t, n in self.presets.items())
            return 200, _envelope(f"<tptz:GetPresetsResponse>{items}</tptz:GetPresetsResponse>")
        if action == "SetPreset":
            name = _text(body, "PresetName") or "Preset"
            with self.lock:
                token = _text(body, "PresetToken") or str(self._next)
                self._next += 1
                self.presets[token] = name
            return 200, _envelope(f"<tptz:SetPresetResponse><tptz:PresetToken>{token}</tptz:PresetToken>"
                                  "</tptz:SetPresetResponse>")
        if action == "RemovePreset":
            with self.lock:
                if self.presets.pop(_text(body, "PresetToken") or "", None) is None:
                    return 500, _fault("No such preset")
            return 200, _envelope("<tptz:RemovePresetResponse/>")
        if action == "GotoPreset":
            if (_text(body, "PresetToken") or "") not in self.presets:
                return 500, _fault("No such preset")
            return 200, _envelope("<tptz:GotoPresetResponse/>")
        if action in ("Stop", "GotoHomePosition", "SetHomePosition"):
            return 200, _envelope(f"<tptz:{action}Response/>")
        return 500, _fault(f"Not implemented: {action}")

    def _handler(self):
        cam = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # quiet
                pass

            def do_POST(self):
                data = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                root = ET.fromstring(data)
                body = next(el for el in root.iter() if el.tag.endswith("}Body"))
                op = list(body)[0]
                action = re.sub(r"^\{.*\}", "", op.tag)
                # WS-Security UsernameToken: refuse a wrong login, as cameras do.
                user = _find(root, "Username")
                if user is None:
                    code, text = 400, _fault("Sender not authorized")
                elif cam.password == "" or _password_ok(root, cam.password):
                    with cam.lock:
                        cam.calls.append((action, op))
                    if action in cam.hang:
                        threading.Event().wait(30)
                        return
                    code, text = cam.reply(action, op)
                else:
                    code, text = 400, _fault("Sender not authorized")
                payload = text.encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/soap+xml; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        return Handler


def _find(el: ET.Element, local: str) -> ET.Element | None:
    return next((x for x in el.iter() if re.sub(r"^\{.*\}", "", x.tag) == local), None)


def _text(el: ET.Element, local: str) -> str | None:
    x = _find(el, local)
    return x.text if x is not None else None


def _password_ok(root: ET.Element, password: str) -> bool:
    """Check a WS-Security UsernameToken PasswordDigest (what zeep sends)."""
    import base64
    import hashlib

    pw = _find(root, "Password")
    nonce = _find(root, "Nonce")
    created = _find(root, "Created")
    if pw is None or nonce is None or created is None:
        return pw is not None and pw.text == password
    digest = base64.b64encode(hashlib.sha1(base64.b64decode(nonce.text) + created.text.encode() + password.encode())
                              .digest()).decode()
    return digest == pw.text
