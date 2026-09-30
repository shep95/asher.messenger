#!/usr/bin/env python3
"""Render a brand profile (brands/<id>/brand.json) into every platform of this monorepo.

    tools/brand/apply.py --brand asher              # apply everything
    tools/brand/apply.py --brand asher --dry-run    # show what would change
    tools/brand/apply.py --brand asher --targets android,desktop

What it touches (see brands/README.md for the full table):
  android   clients/android/brand.properties (generated, gitignored), strings.xml app_name /
            URLs / support e-mail, optional whisper.store + launcher icons from brands/<id>/assets/android/
  ios       project.pbxproj (bundle prefix, team, merchant, product name), TSConstants.swift
            (production endpoints + server keys), Signal-Info.plist (URL scheme, support e-mail),
            *.entitlements (associated domains), signal-messenger.cer (pinned root CA)
  desktop   package.json identity, config/production.json, _locales/en/messages.json app-name keys
  server    deploy/server/<id>/{config.yml,secrets-bundle.yml} from the upstream samples,
            GrpcExceptions.DOMAIN
  libsignal libs/libsignal/brand.env (+ brand-root-ca.der) consumed by tools/brand/build-libsignal.sh

The tool is idempotent and stateless: every edit is keyed on structure (JSON keys, XML `name=`
attributes, Swift `let` names, pbxproj settings), so re-running with another profile moves the tree
to that profile from any starting state. brands/.applied.json only records which brand is applied.
"""
import argparse
import base64
import json
import os
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATE_FILE = ROOT / "brands" / ".applied.json"
ANDROID = ROOT / "clients" / "android"
IOS = ROOT / "clients" / "ios"
DESKTOP = ROOT / "clients" / "desktop"
SERVER = ROOT / "services" / "server"
LIBSIGNAL = ROOT / "libs" / "libsignal"

class Writer:
    """Stages writes in memory; `flush()` commits them, so a failure part-way leaves the tree untouched."""

    def __init__(self, dry_run: bool):
        self.dry_run = dry_run
        self.changed: list[str] = []
        self._pending: dict[Path, bytes] = {}

    def write_text(self, path: Path, content: str) -> None:
        self.write_bytes(path, content.encode())

    def write_bytes(self, path: Path, content: bytes) -> None:
        old = path.read_bytes() if path.exists() else None
        if old == content:
            return
        self.changed.append(str(path.relative_to(ROOT)))
        self._pending[path] = content

    def flush(self) -> None:
        if self.dry_run:
            return
        for path, content in self._pending.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

    def copy(self, src: Path, dst: Path) -> None:
        self.write_bytes(dst, src.read_bytes())


def die(msg: str) -> None:
    sys.exit(f"apply.py: error: {msg}")


def load_brand(brand_id: str) -> tuple[dict, Path]:
    bdir = ROOT / "brands" / brand_id
    bfile = bdir / "brand.json"
    if not bfile.exists():
        die(f"no such brand profile: {bfile}")
    brand = json.loads(bfile.read_text())
    required = ["id", "display_name", "short_name", "desktop_name", "support_email", "url_scheme",
                "urls", "hosts", "link_domains", "android", "ios", "desktop", "server", "crypto", "integrations"]
    for k in required:
        if k not in brand:
            die(f"{bfile}: missing key {k!r}")
    if brand["id"] != brand_id:
        die(f"{bfile}: id {brand['id']!r} does not match directory {brand_id!r}")
    if not re.fullmatch(r"[a-z][a-z0-9+.-]*", brand["url_scheme"]):
        die(f"{bfile}: url_scheme must be a valid URI scheme")
    return brand, bdir


def sub_once(text: str, pattern: str, repl, *, what: str, flags=0, count: int = 1) -> str:
    """re.sub that insists on exactly `count` matches, so upstream layout changes fail loudly."""
    new, n = re.subn(pattern, repl, text, flags=flags)
    if n != count:
        die(f"{what}: expected {count} match(es) for {pattern!r}, found {n} (upstream layout changed?)")
    return new


def root_ca(brand: dict, bdir: Path) -> tuple[bytes, str] | None:
    """Return (der_bytes, pem_text) of the brand's root CA, or None if it uses Signal's."""
    rel = brand["crypto"].get("root_ca")
    if not rel:
        return None
    path = bdir / rel
    if not path.exists():
        die(f"crypto.root_ca points to a missing file: {path}")
    data = path.read_bytes()
    if b"-----BEGIN CERTIFICATE-----" in data:
        pem = data.decode()
        body = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", pem, re.S).group(1)
        der = base64.b64decode("".join(body.split()))
    else:
        der = data
        b64 = base64.b64encode(der).decode()
        pem = "-----BEGIN CERTIFICATE-----\n" + "\n".join(b64[i:i + 64] for i in range(0, len(b64), 64)) + "\n-----END CERTIFICATE-----\n"
    return der, pem.strip() + "\n"


def https(host: str) -> str:
    return f"https://{host}"


# ----------------------------------------------------------------------------- android

def apply_android(brand: dict, bdir: Path, w: Writer, state: dict) -> None:
    h, c, a, u = brand["hosts"], brand["crypto"], brand["android"], brand["urls"]
    props = {
        "APPLICATION_ID": a["application_id"],
        "ARCHIVES_BASE_NAME": a["archives_base_name"],
        "MAPS_KEY": a["maps_key"],
        "GIPHY_API_KEY": a["giphy_api_key"],
        "DISABLE_STATIC_IPS": "true" if a.get("disable_static_ips") else "false",
        "SIGNAL_URL": https(h["chat"]),
        "STORAGE_URL": https(h["storage"]),
        "SIGNAL_CDN_URL": https(h["cdn0"]),
        "SIGNAL_CDN2_URL": https(h["cdn2"]),
        "SIGNAL_CDN3_URL": https(h["cdn3"]),
        "SIGNAL_CDSI_URL": https(h["cdsi"]),
        "SIGNAL_SERVICE_STATUS_URL": h["status"],
        "SIGNAL_SVR2_URL": https(h["svr2"]),
        "SIGNAL_SFU_URL": https(h["sfu"]),
        "SIGNAL_STAGING_SFU_URL": https(h["sfu_staging"]),
        "SIGNAL_SFU_INTERNAL_URLS": ",".join([https(h["sfu_test"]), https(h["sfu_staging"]), https(h["sfu_staging"])]),
        "CONTENT_PROXY_HOST": h["content_proxy"],
        "SVR2_MRENCLAVE": c["svr2_mrenclave"],
        "SVR2_MRENCLAVE_LEGACY": c["svr2_mrenclave_legacy"],
        "UNIDENTIFIED_SENDER_TRUST_ROOTS": ",".join(c["ud_trust_roots"]),
        "ZKGROUP_SERVER_PUBLIC_PARAMS": c["zkgroup_server_public_params"],
        "GENERIC_SERVER_PUBLIC_PARAMS": c["generic_server_public_params"],
        "BACKUP_SERVER_PUBLIC_PARAMS": c["backup_server_public_params"],
        "DEFAULT_CURRENCIES": brand["integrations"]["default_currencies"],
        "SIGNAL_CAPTCHA_URL": h["captcha_registration_url"],
        "RECAPTCHA_PROOF_URL": h["captcha_challenge_url"],
        "BADGE_STATIC_ROOT": https(h["updates2"]) + "/static/badges/",
        "STRIPE_PUBLISHABLE_KEY": brand["integrations"]["stripe_publishable_key"],
    }
    lines = [f"# Generated by tools/brand/apply.py from brands/{brand['id']}/brand.json. Do not edit; do not commit.",
             f"BRAND_ID={brand['id']}"]
    lines += [f"{k}={v}" for k, v in props.items()]
    w.write_text(ANDROID / "brand.properties", "\n".join(lines) + "\n")

    strings = ANDROID / "app/src/main/res/values/strings.xml"
    text = strings.read_text()
    values = {
        "app_name": brand["display_name"],
        "install_url": u["install"],
        "donate_url": u["donate"],
        "support_center_url": u["support_center"],
        "terms_and_privacy_policy_url": u["legal"],
        "signal_me_username_url": f"https://{brand['link_domains']['me']}/#u/%1$s",
        "SupportEmailUtil_support_email": brand["support_email"],
    }
    for name, value in values.items():
        pat = re.compile(r'(<string name="%s" translatable="false">)(.*?)(</string>)' % re.escape(name))
        if not pat.search(text):
            die(f"strings.xml: string {name!r} not found")
        text = pat.sub(lambda m: m.group(1) + xml_escape(value) + m.group(3), text, count=1)
    w.write_text(strings, text)

    assets = bdir / "assets" / "android"
    if (assets / "whisper.store").exists():
        w.copy(assets / "whisper.store", ANDROID / "app/src/main/res/raw/whisper.store")
    for icon in sorted(assets.glob("mipmap-*/ic_launcher*.png")) if assets.exists() else []:
        w.copy(icon, ANDROID / "app/src/main/res" / icon.parent.name / icon.name)


def xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")


# ----------------------------------------------------------------------------- ios

def apply_ios(brand: dict, bdir: Path, w: Writer, state: dict) -> None:
    h, c, i, u, ld = brand["hosts"], brand["crypto"], brand["ios"], brand["urls"], brand["link_domains"]

    pbx = IOS / "Signal.xcodeproj/project.pbxproj"
    text = pbx.read_text()
    for key, value in [("SIGNAL_BUNDLEID_PREFIX", i["bundle_id_prefix"]), ("SIGNAL_MERCHANTID", i["merchant_id"]),
                       ("DEVELOPMENT_TEAM", i["team_id"])]:
        pat = re.compile(r"(\b%s = )(\"[^\"]*\"|[^;]+)(;)" % key)
        if not pat.search(text):
            die(f"pbxproj: {key} not found")
        text = pat.sub(lambda m: m.group(1) + pbx_quote(value) + m.group(3), text)
    # PRODUCT_NAME only in the main app target's build configurations, identified by their bundle id.
    app_bundle = 'PRODUCT_BUNDLE_IDENTIFIER = "$(SIGNAL_BUNDLEID_PREFIX).signal";'

    def rename_product(m: re.Match) -> str:
        block = m.group(0)
        if app_bundle not in block:
            return block
        return re.sub(r"PRODUCT_NAME = (\"[^\"]*\"|[^;]+);", f"PRODUCT_NAME = {pbx_quote(brand['display_name'])};", block, count=1)

    before = text
    text = re.sub(r"buildSettings = \{.*?\n\t\t\t\};", rename_product, text, flags=re.S)
    if text.count(f"PRODUCT_NAME = {pbx_quote(brand['display_name'])};") < 4:
        die("pbxproj: could not find the four app-target PRODUCT_NAME settings")
    w.write_text(pbx, text)

    ts = IOS / "SignalServiceKit/Environment/TSConstants.swift"
    text = ts.read_text()
    start = text.index("public class TSConstantsProduction")
    end = text.index("// MARK: - Staging")
    prod = text[start:end]
    swift_strings = {
        "mainServiceURL": https(h["chat"]),
        "textSecureCDN0ServerURL": https(h["cdn0"]),
        "textSecureCDN2ServerURL": https(h["cdn2"]),
        "textSecureCDN3ServerURL": https(h["cdn3"]),
        "storageServiceURL": https(h["storage"]),
        "sfuURL": https(h["sfu"]),
        "sfuTestURL": https(h["sfu_test"]),
        "svr2URL": f"wss://{h['svr2']}",
        "registrationCaptchaURL": h["captcha_registration_url"],
        "challengeCaptchaURL": h["captcha_challenge_url"],
        "updatesURL": https(h["updates"]),
        "updates2URL": https(h["updates2"]),
    }
    for name, value in swift_strings.items():
        pat = re.compile(r'(public let %s = ")([^"]*)(")' % name)
        if not pat.search(prod):
            die(f"TSConstants.swift: production {name!r} not found")
        prod = pat.sub(lambda m: m.group(1) + value + m.group(3), prod, count=1)
    for name, value in [("serverPublicParams", c["zkgroup_server_public_params"]),
                        ("callLinkPublicParams", c["generic_server_public_params"]),
                        ("backupServerPublicParams", c["backup_server_public_params"])]:
        pat = re.compile(r'(public let %s = Data\(base64Encoded: ")([^"]*)("\)!)' % name)
        if not pat.search(prod):
            die(f"TSConstants.swift: production {name!r} not found")
        prod = pat.sub(lambda m: m.group(1) + value + m.group(3), prod, count=1)
    roots = ", ".join(f'"{r}"' for r in c["ud_trust_roots"])
    prod, n = re.subn(r"public let kUDTrustRoots = \[[^\]]*\]", f"public let kUDTrustRoots = [{roots}]", prod, count=1)
    if n != 1:
        die("TSConstants.swift: kUDTrustRoots not found")
    enclaves = "".join(f'        MrEnclave("{e}"),\n' for e in c["ios_svr2_enclaves"])
    prod, n = re.subn(r"public let svr2Enclaves = \[\n(?:        MrEnclave\(\"[^\"]*\"\),\n)*    \]",
                      f"public let svr2Enclaves = [\n{enclaves}    ]", prod, count=1)
    if n != 1:
        die("TSConstants.swift: svr2Enclaves not found")
    text = text[:start] + prod + text[end:]
    for name, value in [("legalTermsUrl", u["legal"].rstrip("/") + "/"), ("donateUrl", u["donate"].rstrip("/") + "/"),
                        ("appStoreUrl", u["app_store"])]:
        text = sub_once(text, r'(public static let %s = URL\(string: ")[^"]*("\)!)' % name,
                        lambda m, v=value: m.group(1) + v + m.group(2), what=f"TSConstants.swift {name}")
    w.write_text(ts, text)

    plist = IOS / "Signal/Signal-Info.plist"
    text = plist.read_text()
    text, n = re.subn(r"(<key>CFBundleURLSchemes</key>\s*<array>\s*<string>)([^<]*)(</string>)",
                      lambda m: m.group(1) + brand["url_scheme"] + m.group(3), text, count=1)
    if n != 1:
        die("Signal-Info.plist: CFBundleURLSchemes not found")
    text, n = re.subn(r"(<key>LOGS_EMAIL</key>\s*<string>)([^<]*)(</string>)",
                      lambda m: m.group(1) + brand["support_email"] + m.group(3), text, count=1)
    if n != 1:
        die("Signal-Info.plist: LOGS_EMAIL not found")
    w.write_text(plist, text)

    website_host = re.sub(r"^https?://", "", u["website"]).split("/")[0]
    domains = [ld["art"], ld["tube"], ld["group"], ld["me"], ld["donations"], ld["link"]]
    array = "".join(f"\t\t<string>applinks:{d}</string>\n" for d in domains) + f"\t\t<string>webcredentials:{website_host}</string>\n"
    for ent in [IOS / "Signal/Signal.entitlements", IOS / "Signal/Signal-AppStore.entitlements"]:
        text = ent.read_text()
        text, n = re.subn(r"(<key>com\.apple\.developer\.associated-domains</key>\n\t<array>\n)(?:\t\t<string>[^<]*</string>\n)*(\t</array>)",
                          lambda m: m.group(1) + array + m.group(2), text, count=1)
        if n != 1:
            die(f"{ent.name}: associated-domains not found")
        w.write_text(ent, text)

    ca = root_ca(brand, bdir)
    if ca:
        w.write_bytes(IOS / "SignalServiceKit/Resources/Certificates/signal-messenger.cer", ca[0])



def pbx_quote(v: str) -> str:
    return v if re.fullmatch(r"[A-Za-z0-9_.$/-]+", v) else f'"{v}"'


# ----------------------------------------------------------------------------- desktop

def dump_json(obj) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def apply_desktop(brand: dict, bdir: Path, w: Writer, state: dict) -> None:
    h, c, d, u = brand["hosts"], brand["crypto"], brand["desktop"], brand["urls"]

    pkg_path = DESKTOP / "package.json"
    pkg = json.loads(pkg_path.read_text())
    pkg["name"] = d["package_name"]
    pkg["productName"] = brand["display_name"]
    pkg["description"] = d["description"]
    pkg["desktopName"] = d["desktop_name"]
    pkg["repository"] = u.get("repository", pkg["repository"])
    pkg["author"] = {"name": brand.get("organization", brand["display_name"]), "email": brand["support_email"]}
    b = pkg["build"]
    b["appId"] = d["app_id"]
    for platform in ("mac", "win"):
        for pub in b[platform].get("publish", []):
            if pub.get("provider") == "generic":
                pub["url"] = d["updates_url"]
    b["linux"]["executableName"] = d["executable_name"]
    b["linux"]["desktop"]["entry"]["StartupWMClass"] = d["startup_wm_class"]
    b["protocols"] = {"name": f"{brand['url_scheme']}-url-scheme", "schemes": [brand["url_scheme"], "signalcaptcha"]}
    w.write_text(pkg_path, dump_json(pkg))

    prod_path = DESKTOP / "config/production.json"
    prod = json.loads(prod_path.read_text())
    prod.update({
        "serverUrl": https(h["chat"]),
        "storageUrl": https(h["storage"]),
        "cdn": {"0": https(h["cdn0"]), "2": https(h["cdn2"]), "3": https(h["cdn3"])},
        "sfuUrl": https(h["sfu"]) + "/",
        "challengeUrl": h["captcha_challenge_url"],
        "registrationChallengeUrl": h["captcha_registration_url"],
        "serverPublicParams": c["zkgroup_server_public_params"],
        "serverTrustRoots": list(c["ud_trust_roots"]),
        "genericServerPublicParams": c["generic_server_public_params"],
        "backupServerPublicParams": c["backup_server_public_params"],
        "stripePublishableKey": brand["integrations"]["stripe_publishable_key"],
    })
    upstream = brand["id"] == "signal-upstream"
    extra_keys = ["contentProxyUrl", "updatesUrl", "resourcesUrl", "updatesPublicKey", "appImageUpdatesPublicKey", "certificateAuthority"]
    if upstream:
        for k in extra_keys:
            prod.pop(k, None)
    else:
        prod["contentProxyUrl"] = f"http://{h['content_proxy']}:443"
        prod["updatesUrl"] = d["updates_url"]
        prod["resourcesUrl"] = https(h["updates2"])
        prod["updatesPublicKey"] = d["updates_public_key"]
        prod["appImageUpdatesPublicKey"] = d["app_image_updates_public_key"]
        ca = root_ca(brand, bdir)
        if ca:
            prod["certificateAuthority"] = ca[1].strip()
        else:
            prod.pop("certificateAuthority", None)
    w.write_text(prod_path, dump_json(prod))

    loc_path = DESKTOP / "_locales/en/messages.json"
    loc = json.loads(loc_path.read_text())
    loc["icu:signalDesktop"]["messageformat"] = brand["desktop_name"]
    loc["icu:aboutSignalDesktop"]["messageformat"] = f"About {brand['desktop_name']}"
    w.write_text(loc_path, dump_json(loc))


# ----------------------------------------------------------------------------- server

def apply_server(brand: dict, bdir: Path, w: Writer, state: dict) -> None:
    h, c, s, i, a = brand["hosts"], brand["crypto"], brand["server"], brand["ios"], brand["android"]
    out = ROOT / "deploy" / "server" / brand["id"]
    sample = (SERVER / "service/config/sample.yml").read_text()

    def sub(pattern: str, repl: str, text: str, what: str) -> str:
        new, n = re.subn(pattern, repl, text, count=1, flags=re.M)
        if n != 1:
            die(f"sample.yml: {what} not found (upstream layout changed?)")
        return new

    ios_bundle = f"{i['bundle_id_prefix']}.signal"
    text = sample
    text = sub(r"^(  packageName: )package\.name$", rf"\g<1>{a['application_id']}", text, "googlePlayBilling.packageName")
    text = re.sub(r"^(  bundleId: )bundle\.name$", rf"\g<1>{ios_bundle}", text, flags=re.M)
    text = sub(r"^(  bundleId: )com\.example\.textsecuregcm$", rf"\g<1>{ios_bundle}", text, "apn.bundleId")
    text = sub(r"^(  domain: )example\.com$", rf"\g<1>{s['attachments_domain']}", text, "gcpAttachments.domain")
    text = sub(r"^(  relyingPartyId: )example\.org$", rf"\g<1>{s['webauthn_relying_party_id']}", text, "registrationWebAuthn.relyingPartyId")
    text = sub(r"^(    - )https://example\.org$", rf"\g<1>{brand['urls']['website']}", text, "registrationWebAuthn.origins")
    zk = c["zkgroup_server_public_params"]
    text = sub(r"^(  serverPublic: )ABCDEFGHIJ\S+$", rf"\g<1>{zk}", text, "groupsZkConfig.serverPublic")
    header = (f"# Generated by tools/brand/apply.py from brands/{brand['id']}/brand.json on top of\n"
              f"# services/server/service/config/sample.yml. Brand-specific values are filled in; every other\n"
              f"# value is the upstream placeholder and must be replaced for your infrastructure\n"
              f"# (see docs/self-hosting.md). Keep the real file out of git.\n")
    w.write_text(out / "config.yml", header + text)
    secrets = (SERVER / "service/config/sample-secrets-bundle.yml").read_text()
    w.write_text(out / "secrets-bundle.yml", "# Copied from the upstream sample by tools/brand/apply.py. Fill in with tools/server/gen-keys.sh output.\n" + secrets)

    grpc = SERVER / "service/src/main/java/org/whispersystems/textsecuregcm/grpc/GrpcExceptions.java"
    text = grpc.read_text()
    text = sub_once(text, r'(public static final String DOMAIN = ")[^"]*(";)', lambda m: m.group(1) + h["chat_grpc"] + m.group(2),
                    what="GrpcExceptions.DOMAIN")
    w.write_text(grpc, text)


# ----------------------------------------------------------------------------- libsignal

def apply_libsignal(brand: dict, bdir: Path, w: Writer, state: dict) -> None:
    h, c = brand["hosts"], brand["crypto"]
    env_path = LIBSIGNAL / "brand.env"
    ca_path = LIBSIGNAL / "brand-root-ca.der"
    if not brand.get("libsignal_override"):
        if env_path.exists():
            w.write_text(env_path, f"# brand {brand['id']} does not override libsignal; Production is Signal's environment.\n")
        return
    lines = [f"# Generated by tools/brand/apply.py from brands/{brand['id']}/brand.json. Source this before building libsignal.",
             f"export LIBSIGNAL_BRAND_CHAT_HOST={h['chat_grpc']}",
             f"export LIBSIGNAL_BRAND_CDSI_HOST={h['cdsi']}",
             f"export LIBSIGNAL_BRAND_SVR2_HOST={h['svr2']}",
             f"export LIBSIGNAL_BRAND_SVRB_HOST={h['svrb']}"]
    for var, key in [("LIBSIGNAL_BRAND_CDSI_MRENCLAVE", "cdsi_mrenclave"), ("LIBSIGNAL_BRAND_SVR2_MRENCLAVE", "svr2_mrenclave"),
                     ("LIBSIGNAL_BRAND_SVRB_MRENCLAVE", "svrb_mrenclave"), ("LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID", "svr2_raft_group_id"),
                     ("LIBSIGNAL_BRAND_SVRB_RAFT_GROUP_ID", "svrb_raft_group_id"), ("LIBSIGNAL_BRAND_KEYTRANS_SIGNING_KEY", "keytrans_signing_key"),
                     ("LIBSIGNAL_BRAND_KEYTRANS_VRF_KEY", "keytrans_vrf_key")]:
        v = c.get(key)
        if v is None or v == "REPLACE_ME":
            lines.append(f"# {var} not set: {key} is {v!r} in brand.json (Signal's value is compiled in until you fill it)")
        else:
            lines.append(f"export {var}={v}")
    ca = root_ca(brand, bdir)
    if ca:
        w.write_bytes(ca_path, ca[0])
        lines.append(f"export LIBSIGNAL_BRAND_ROOT_CA_DER={ca_path}")
    else:
        lines.append("# LIBSIGNAL_BRAND_ROOT_CA_DER not set: platform trust store will be used")
    w.write_text(env_path, "\n".join(lines) + "\n")


TARGETS = {"android": apply_android, "ios": apply_ios, "desktop": apply_desktop, "server": apply_server, "libsignal": apply_libsignal}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--brand", required=True, help="brand id (directory under brands/)")
    ap.add_argument("--targets", default=",".join(TARGETS), help="comma-separated subset of: " + ",".join(TARGETS))
    ap.add_argument("--dry-run", action="store_true", help="report changes without writing")
    args = ap.parse_args()
    brand, bdir = load_brand(args.brand)
    targets = [t.strip() for t in args.targets.split(",") if t.strip()]
    for t in targets:
        if t not in TARGETS:
            die(f"unknown target {t!r}")
    state: dict = {}
    w = Writer(args.dry_run)
    for t in targets:
        TARGETS[t](brand, bdir, w, state)
    w.flush()
    if not args.dry_run:
        STATE_FILE.write_text(json.dumps({"brand": brand["id"]}, indent=2) + "\n")
    verb = "would change" if args.dry_run else "changed"
    if w.changed:
        print(f"brand {brand['id']}: {verb} {len(w.changed)} file(s):")
        for f in w.changed:
            print(f"  {f}")
    else:
        print(f"brand {brand['id']}: nothing to change")
    placeholders = [p for p in re.findall(r'"([^"]*REPLACE_ME[^"]*)"', (bdir / "brand.json").read_text())]
    if placeholders and not args.dry_run:
        print(f"note: {len(placeholders)} REPLACE_ME placeholder(s) remain in brands/{brand['id']}/brand.json; builds will not reach a server until they are filled in.")


if __name__ == "__main__":
    main()
