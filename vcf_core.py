# -*- coding: utf-8 -*-
"""
vcf_core - everything VCF Desk does that isn't UI:
vCard 2.1/3.0/4.0 read & write, Excel/CSV/Access import, and export to
Android, iPhone, Google, Outlook, Excel and Access formats.
"""

import base64
import csv
import datetime
import os
import quopri
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape as xml_escape

IS_WIN = sys.platform.startswith("win")

# =============================================================================
#  vCard core
# =============================================================================

BINARY_PROPS = {"PHOTO", "LOGO", "SOUND", "KEY"}
STRUCTURED = {"N", "ADR", "ORG"}
TEXT_PROPS = {"FN", "TITLE", "TEL", "EMAIL", "URL", "BDAY", "NOTE"}
MULTI_EDIT = {"TEL", "EMAIL", "ADR", "URL"}          # fully managed by the form
SINGLE_EDIT = {"FN", "N", "ORG", "TITLE", "BDAY", "NOTE"}  # first one managed
ENCODING_WORDS = {"QUOTED-PRINTABLE", "BASE64", "8BIT", "7BIT", "B"}


def unescape(s):
    out, i = [], 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s):
            nx = s[i + 1]
            out.append("\n" if nx in "nN" else nx)
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def escape(s):
    return (s.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n")
             .replace(";", "\\;").replace(",", "\\,"))


def split_unescaped(s, sep=";"):
    parts, cur, i = [], [], 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s):
            cur.append(s[i:i + 2])
            i += 2
            continue
        if ch == sep:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def _split_line(line):
    inq = False
    for i, ch in enumerate(line):
        if ch == '"':
            inq = not inq
        elif ch == ":" and not inq:
            return line[:i], line[i + 1:]
    return line, ""


def _split_params(s):
    parts, cur, inq = [], [], False
    for ch in s:
        if ch == '"':
            inq = not inq
            cur.append(ch)
        elif ch == ";" and not inq:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


class Prop:
    __slots__ = ("name", "value", "params", "group")

    def __init__(self, name, value="", params=None, group=""):
        self.name = name.upper()
        self.value = value
        self.params = params if params is not None else []
        self.group = group

    def copy(self):
        v = list(self.value) if isinstance(self.value, list) else self.value
        return Prop(self.name, v, [list(p) for p in self.params], self.group)

    def types(self):
        out = []
        for k, v in self.params:
            if k == "TYPE":
                out += [t.strip().strip('"').upper() for t in v.split(",") if t.strip()]
        return out

    def param(self, key):
        for k, v in self.params:
            if k == key:
                return v.strip('"')
        return None


class Contact:
    def __init__(self, props=None, version="3.0"):
        self.props = props if props is not None else []
        self.version = version

    def get(self, name):
        for p in self.props:
            if p.name == name:
                return p
        return None

    def all(self, name):
        return [p for p in self.props if p.name == name]

    def text(self, name):
        p = self.get(name)
        if p is None:
            return ""
        return " ".join(x for x in p.value if x) if isinstance(p.value, list) else p.value

    def name_parts(self):
        p = self.get("N")
        n = list(p.value) if p is not None and isinstance(p.value, list) else []
        return (n + [""] * 5)[:5]  # last, first, middle, prefix, suffix

    def display_name(self):
        fn = self.text("FN").strip()
        if fn:
            return fn
        last, first, middle, prefix, suffix = self.name_parts()
        nm = compose_name(prefix, first, middle, last, suffix)
        if nm:
            return nm
        for key in ("ORG", "EMAIL", "TEL"):
            t = self.text(key).strip()
            if t:
                return t
        return "(No name)"

    def subtitle(self):
        for key in ("TEL", "EMAIL", "ORG"):
            t = self.text(key).strip()
            if t:
                return t.replace("tel:", "")
        return ""

    def search_blob(self):
        bits = [self.display_name()]
        for p in self.props:
            if p.name in ("TEL", "EMAIL", "ORG", "TITLE", "NOTE", "N", "ADR", "NICKNAME"):
                bits.append(" ".join(p.value) if isinstance(p.value, list) else p.value)
        blob = " ".join(bits).casefold()
        return blob + " " + re.sub(r"\D", "", blob)

    def has_photo(self):
        return self.get("PHOTO") is not None


def compose_name(prefix, first, middle, last, suffix):
    return " ".join(x.strip() for x in (prefix, first, middle, last, suffix) if x and x.strip())


def _logical_lines(text):
    raw = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out, pending_qp = [], False
    for line in raw:
        if out and pending_qp:
            out[-1] = out[-1][:-1] + (line[1:] if line[:1] in (" ", "\t") else line)
        elif line[:1] in (" ", "\t") and out:
            out[-1] += line[1:]
        else:
            out.append(line)
        cur = out[-1] if out else ""
        pending_qp = cur.endswith("=") and "QUOTED-PRINTABLE" in _split_line(cur)[0].upper()
    return out


def _make_prop(line):
    namepart, value = _split_line(line)
    bits = _split_params(namepart)
    name, group = bits[0].strip(), ""
    if "." in name:
        group, name = name.rsplit(".", 1)
    params = []
    for b in bits[1:]:
        b = b.strip()
        if not b:
            continue
        if "=" in b:
            k, v = b.split("=", 1)
            params.append([k.strip().upper(), v.strip()])
        elif b.upper() in ENCODING_WORDS:
            params.append(["ENCODING", b.upper()])
        else:
            params.append(["TYPE", b])
    enc = next((v.upper() for k, v in params if k == "ENCODING"), "")
    charset = next((v.strip('"') for k, v in params if k == "CHARSET"), "") or "utf-8"
    if enc == "QUOTED-PRINTABLE":
        data = quopri.decodestring(value.encode("utf-8"))
        try:
            value = data.decode(charset, errors="replace")
        except LookupError:
            value = data.decode("utf-8", errors="replace")
        params = [p for p in params if p[0] not in ("ENCODING", "CHARSET")]
    name = name.upper()
    if name in BINARY_PROPS:
        value = re.sub(r"\s+", "", value)
    elif name in STRUCTURED:
        value = [unescape(x) for x in split_unescaped(value, ";")]
    elif name in TEXT_PROPS:
        value = unescape(value)
    return Prop(name, value, params, group)


def parse_vcf(text):
    contacts, cur = [], None
    for line in _logical_lines(text):
        if not line.strip():
            continue
        head, val = _split_line(line)
        key = head.strip().upper()
        if key == "BEGIN" and val.strip().upper() == "VCARD":
            cur = Contact(version="2.1")
            continue
        if key == "END" and val.strip().upper() == "VCARD":
            if cur is not None:
                contacts.append(cur)
            cur = None
            continue
        if cur is None:
            continue
        if key == "VERSION":
            cur.version = val.strip() or "3.0"
            continue
        try:
            cur.props.append(_make_prop(line))
        except Exception:
            cur.props.append(Prop("X-UNPARSED", line))
    return contacts


def read_vcf_file(path):
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        text = data.decode("utf-16")
    else:
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            text = data.decode("cp1256", errors="replace")
    return parse_vcf(text)


def _fold(line):
    out, cur, cur_len, limit = [], [], 0, 75
    for ch in line:
        n = len(ch.encode("utf-8"))
        if cur_len + n > limit:
            out.append("".join(cur))
            cur, cur_len, limit = [" "], 1, 75
        cur.append(ch)
        cur_len += n
    out.append("".join(cur))
    return "\r\n".join(out)


def _quote_param(v):
    if v.startswith('"'):
        return v
    return '"%s"' % v if any(c in v for c in ":;,") else v


def _photo_parts(p):
    """(base64 data, TYPE) for a PHOTO/LOGO property, or (None, None) if it is a URL."""
    v = p.value
    if v.startswith("data:"):
        m = re.match(r"data:image/([\w.+-]+);base64,(.*)", v, re.S)
        if not m:
            return None, None
        t = m.group(1).upper()
        return m.group(2), ("JPEG" if t in ("JPG", "JPEG") else t)
    enc = (p.param("ENCODING") or "").upper()
    if enc in ("B", "BASE64") or (not re.match(r"^[A-Za-z][A-Za-z0-9+.\-]*:", v) and len(v) > 64):
        t = (p.param("TYPE") or "").upper().replace("IMAGE/", "")
        return v, (t or "JPEG")
    return None, None


def _qp_encode(text):
    """Quoted-printable (UTF-8) with soft line breaks, the way Android writes vCard 2.1."""
    toks = []
    for b in text.replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8"):
        toks.append(chr(b) if (33 <= b <= 126 and b != 61) or b == 32 else "=%02X" % b)
    lines, cur = [], ""
    for t in toks:
        if len(cur) + len(t) > 74:
            lines.append(cur + "=")
            cur = ""
        cur += t
    lines.append(cur)
    return "\r\n".join(lines)


def _prop_line(p, ver):
    name = (p.group + "." if p.group else "") + p.name
    if p.name in BINARY_PROPS:
        data, typ = _photo_parts(p)
        if data is None:  # a link
            extra = [x for x in p.params if x[0] not in ("ENCODING", "CHARSET")]
            if ver == "2.1":
                return name + ";VALUE=URL:" + p.value
            return name + "".join(";%s=%s" % (k, v) for k, v in extra) + ":" + p.value
        if ver == "4.0":
            return name + ":data:image/%s;base64,%s" % (typ.lower(), data)
        if ver == "2.1":
            chunks = [data[i:i + 72] for i in range(0, len(data), 72)] or [""]
            return name + ";ENCODING=BASE64;" + typ + ":" + "\r\n  ".join(chunks) + "\r\n"
        return name + ";ENCODING=b;TYPE=" + typ + ":" + data
    params, types = [], []
    is_uri = False
    for k, v in p.params:
        if k == "TYPE":
            types += [t.strip().strip('"') for t in v.split(",") if t.strip()]
        elif k in ("CHARSET", "ENCODING"):
            continue
        elif k == "VALUE" and v.lower() == "uri" and p.name == "TEL":
            is_uri = True
            if ver != "4.0":
                continue
            params.append("VALUE=uri")
        else:
            params.append("%s=%s" % (k, _quote_param(v)))
    seen, uniq = set(), []
    for t in types:
        t2 = t.lower() if ver == "4.0" else t.upper()
        if t2 not in seen:
            seen.add(t2)
            uniq.append(t2)
    value = p.value
    if p.name == "TEL" and isinstance(value, str) and value.lower().startswith("tel:") and ver != "4.0":
        value = value[4:]
    if ver == "2.1":
        if isinstance(value, list):
            val = ";".join(x.replace(";", "\\;") for x in value)
        else:
            val = value
        head = name + "".join(";" + t for t in uniq) + "".join(";" + x for x in params)
        if any(ord(ch) > 126 for ch in val) or "\n" in val:
            return head + ";CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:" + _qp_encode(val)
        return head + ":" + val
    if uniq:
        params.insert(0, "TYPE=" + ",".join(uniq))
    if isinstance(value, list):
        val = ";".join(escape(x) for x in value)
    elif p.name in TEXT_PROPS:
        val = escape(value)
    else:
        val = value.replace("\r\n", "\n").replace("\n", "\\n")
    return name + "".join(";" + x for x in params) + ":" + val


def serialize_contact(c, version=None):
    """version: '2.1' (Android), '3.0' (iPhone/Google/most apps) or '4.0'. Default keeps 4.0 files
    as 4.0 and writes everything else as 3.0."""
    ver = version or ("4.0" if c.version.strip() == "4.0" else "3.0")
    props = list(c.props)
    if not any(p.name == "FN" for p in props):
        props.insert(0, Prop("FN", c.display_name() if c.display_name() != "(No name)" else ""))
    if ver != "4.0" and not any(p.name == "N" for p in props):
        props.insert(1, Prop("N", ["", "", "", "", ""]))
    lines = ["BEGIN:VCARD", "VERSION:" + ver]
    for p in props:
        line = _prop_line(p, ver)
        lines.append(line if ver == "2.1" else _fold(line))
    lines.append("END:VCARD")
    return "\r\n".join(lines) + "\r\n"


def serialize_all(contacts, version=None):
    return "".join(serialize_contact(c, version) for c in contacts)


def write_vcf_file(path, contacts, version=None):
    data = serialize_all(contacts, version).encode("utf-8")
    tmp = path + ".tmp"
    with open(tmp, "wb") as fh:
        fh.write(data)
    os.replace(tmp, path)


# ---- form mapping -----------------------------------------------------------

LABELS = {
    "TEL": ["Mobile", "Home", "Work", "Home fax", "Work fax", "Pager", "Other"],
    "EMAIL": ["Home", "Work", "Other"],
    "ADR": ["Home", "Work", "Other"],
}
LABEL_TYPES = {
    "TEL": {"Mobile": ["CELL"], "Home": ["HOME", "VOICE"], "Work": ["WORK", "VOICE"],
            "Home fax": ["HOME", "FAX"], "Work fax": ["WORK", "FAX"],
            "Pager": ["PAGER"], "Other": ["VOICE"]},
    "EMAIL": {"Home": ["INTERNET", "HOME"], "Work": ["INTERNET", "WORK"], "Other": ["INTERNET"]},
    "ADR": {"Home": ["HOME"], "Work": ["WORK"], "Other": []},
}


AB_LABELS = {"MOBILE": "Mobile", "IPHONE": "Mobile", "HOME": "Home", "WORK": "Work",
             "HOMEFAX": "Home fax", "WORKFAX": "Work fax", "PAGER": "Pager", "OTHER": "Other",
             "MAIN": "Mobile"}


def _ab_label(prop, contact):
    if contact is None or not prop.group:
        return None
    for p in contact.props:
        if p.name == "X-ABLABEL" and p.group.lower() == prop.group.lower():
            key = re.sub(r"[^A-Za-z]", "", p.value.replace("_$!<", "").replace(">!$_", "")).upper()
            lab = AB_LABELS.get(key)
            if lab in LABELS.get(prop.name, []):
                return lab
            return None
    return None


def label_for(prop, contact=None):
    ab = _ab_label(prop, contact)
    if ab:
        return ab
    t = set(prop.types())
    if prop.name == "TEL":
        if "FAX" in t:
            return "Work fax" if "WORK" in t else "Home fax"
        if "CELL" in t or "MOBILE" in t or "IPHONE" in t:
            return "Mobile"
        if "PAGER" in t:
            return "Pager"
    if "WORK" in t:
        return "Work"
    if "HOME" in t:
        return "Home"
    if prop.name == "TEL" and not t - {"PREF"}:
        return "Mobile"
    return "Other"


def contact_to_form(c):
    last, first, middle, prefix, suffix = c.name_parts()
    fn = c.text("FN")
    auto = compose_name(prefix, first, middle, last, suffix)
    org = c.get("ORG")
    orgv = (list(org.value) if org is not None and isinstance(org.value, list) else []) + ["", ""]

    def tel_val(p):
        v = p.value
        return v[4:] if v.lower().startswith("tel:") else v

    return {
        "first": first, "last": last, "middle": middle, "prefix": prefix, "suffix": suffix,
        "fn": "" if fn == auto else fn,
        "company": orgv[0], "department": orgv[1],
        "title": c.text("TITLE"),
        "phones": [(label_for(p, c), tel_val(p), p) for p in c.all("TEL")],
        "emails": [(label_for(p, c), p.value, p) for p in c.all("EMAIL")],
        "addresses": [
            (label_for(p, c),) + tuple((list(p.value) + [""] * 7)[2:7]) + (p,)
            for p in c.all("ADR")
        ],
        "urls": [p.value for p in c.all("URL")],
        "bday": c.text("BDAY"),
        "note": c.text("NOTE").replace("\r\n", "\n"),
    }


def _typed_prop(name, label, value, orig, ver, contact, dropped_groups):
    params = [list(p) for p in orig.params] if orig is not None else []
    group = orig.group if orig is not None else ""
    if orig is None or label != label_for(orig, contact):
        pref = orig is not None and "PREF" in orig.types()
        params = [p for p in params if p[0] != "TYPE"]
        types = list(LABEL_TYPES[name].get(label, []))
        if ver == "4.0":
            types = [t for t in types if t != "INTERNET"]
        if pref:
            types.append("PREF")
        if types:
            params.insert(0, ["TYPE", ",".join(types)])
        if group:
            dropped_groups.add(group.lower())   # its X-ABLabel no longer matches
    return Prop(name, value, params, group)


def apply_form(c, f):
    """Write the form back into the contact, keeping every field in its original place."""
    ver = "4.0" if c.version == "4.0" else "3.0"
    first_single = {}
    for p in c.props:
        if p.name in SINGLE_EDIT and p.name not in first_single:
            first_single[p.name] = p

    def clone(name, value):
        o = first_single.get(name)
        return Prop(name, value, [list(x) for x in o.params] if o else [], o.group if o else "")

    dropped = set()
    new = {}
    auto = compose_name(f["prefix"], f["first"], f["middle"], f["last"], f["suffix"])
    new["FN"] = [clone("FN", f["fn"].strip() or auto or f["company"].strip())]
    new["N"] = [clone("N", [f["last"].strip(), f["first"].strip(), f["middle"].strip(),
                            f["prefix"].strip(), f["suffix"].strip()])]
    o = first_single.get("ORG")
    ov = list(o.value) if o is not None and isinstance(o.value, list) else []
    org = [f["company"].strip(), f["department"].strip()] + ov[2:]
    org += [""] * (len(ov) - len(org))
    new["ORG"] = [clone("ORG", org)] if any(org) else []
    new["TITLE"] = [clone("TITLE", f["title"].strip())] if f["title"].strip() else []
    new["TEL"] = []
    for label, value, orig in f["phones"]:
        value = value.strip()
        if value:
            if orig is not None and orig.value.lower().startswith("tel:"):
                value = "tel:" + value
            new["TEL"].append(_typed_prop("TEL", label, value, orig, ver, c, dropped))
        elif orig is not None and orig.group:
            dropped.add(orig.group.lower())
    new["EMAIL"] = [_typed_prop("EMAIL", label, value.strip(), orig, ver, c, dropped)
                    for label, value, orig in f["emails"] if value.strip()]
    new["ADR"] = []
    for label, street, city, region, postal, country, orig in f["addresses"]:
        comps = [x.strip() for x in (street, city, region, postal, country)]
        if any(comps):
            ov = list(orig.value) if orig is not None and isinstance(orig.value, list) else []
            head = (ov + ["", ""])[:2]
            new["ADR"].append(_typed_prop("ADR", label, head + comps + ov[7:], orig, ver, c, dropped))
    old_urls = c.all("URL")
    new["URL"] = []
    for i, u in enumerate(x.strip() for x in f["urls"]):
        if u:
            p = old_urls[i].copy() if i < len(old_urls) else Prop("URL")
            p.value = u
            new["URL"].append(p)
    new["BDAY"] = [clone("BDAY", f["bday"].strip())] if f["bday"].strip() else []
    new["NOTE"] = [clone("NOTE", f["note"].rstrip())] if f["note"].strip() else []

    out, placed = [], set()
    for p in c.props:
        if p.name in new:
            if p.name in SINGLE_EDIT and p is not first_single.get(p.name):
                out.append(p)          # 2nd TITLE/NOTE/... - not shown in the form, keep it
                continue
            if p.name not in placed:
                placed.add(p.name)
                out += new[p.name]
            continue
        if p.name == "X-ABLABEL" and p.group.lower() in dropped:
            continue
        out.append(p)
    # fields that did not exist before: insert after the last managed field
    order = ["FN", "N", "ORG", "TITLE", "TEL", "EMAIL", "ADR", "URL", "BDAY", "NOTE"]
    for name in order:
        if name in placed or not new[name]:
            continue
        idx = 0
        for i, p in enumerate(out):
            if p.name in order[:order.index(name)]:
                idx = i + 1
        out[idx:idx] = new[name]
        placed.add(name)
    c.props = out


def duplicate_key(c):
    phones = frozenset(re.sub(r"\D", "", p.value)[-9:] for p in c.all("TEL") if re.sub(r"\D", "", p.value))
    emails = frozenset(p.value.strip().lower() for p in c.all("EMAIL"))
    return (c.display_name().casefold().strip(), phones, emails)


# ---- Excel / CSV import -------------------------------------------------------

def _iter(el, local):
    """Element.iter() by local tag name (ignores XML namespaces)."""
    for x in el.iter():
        t = x.tag
        if t == local or t.endswith("}" + local):
            yield x


def _si_text(si):
    """Text of a shared/inline string, skipping phonetic guide runs (<rPh>)."""
    out = []

    def walk(el):
        tag = el.tag.rsplit("}", 1)[-1]
        if tag == "rPh":
            return
        if tag == "t":
            out.append(el.text or "")
        for ch in el:
            walk(ch)
    walk(si)
    return "".join(out)


def _xlsx_cell_value(c, shared):
    t = c.get("t")
    if t == "inlineStr":
        is_ = next(_iter(c, "is"), None)
        return _si_text(is_) if is_ is not None else ""
    v = c.find("{*}v")
    if v is None or v.text is None:
        return ""
    s = v.text
    if t == "s":
        try:
            return shared[int(s)]
        except (ValueError, IndexError):
            return ""
    if t == "b":
        return "TRUE" if s == "1" else "FALSE"
    if t in ("str", "e"):
        return s
    try:
        f = float(s)
        if f.is_integer() and abs(f) < 1e15:
            return str(int(f))
        return repr(f)
    except ValueError:
        return s


def _col_index(ref):
    n = 0
    for ch in ref:
        if ch.isalpha():
            n = n * 26 + (ord(ch.upper()) - 64)
        else:
            break
    return n - 1


def xlsx_sheets(path):
    """[(sheet name, rows)] for every worksheet in an .xlsx file."""
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    shared = []
    if "xl/sharedStrings.xml" in names:
        for si in _iter(ET.fromstring(z.read("xl/sharedStrings.xml")), "si"):
            shared.append(_si_text(si))
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    rels = {}
    if "xl/_rels/workbook.xml.rels" in names:
        for r in _iter(ET.fromstring(z.read("xl/_rels/workbook.xml.rels")), "Relationship"):
            rels[r.get("Id")] = r.get("Target", "")
    out = []
    for i, sh in enumerate(_iter(wb, "sheet")):
        rid = next((v for k, v in sh.attrib.items() if k.endswith("}id")), None)
        target = rels.get(rid, "worksheets/sheet%d.xml" % (i + 1))
        p = target.lstrip("/") if target.startswith("/") else "xl/" + target
        p = os.path.normpath(p).replace("\\", "/")
        if p not in names:
            continue
        rows = []
        for row in _iter(ET.fromstring(z.read(p)), "row"):
            cells = {}
            nxt = 0
            for c in _iter(row, "c"):
                ref = c.get("r")
                idx = _col_index(ref) if ref else nxt
                cells[idx] = _xlsx_cell_value(c, shared).strip()
                nxt = idx + 1
            if cells:
                r = [""] * (max(cells) + 1)
                for k, v in cells.items():
                    r[k] = v
                rows.append(r)
            else:
                rows.append([])
        while rows and not any(rows[-1]):
            rows.pop()
        out.append((sh.get("name") or "Sheet%d" % (i + 1), rows))
    return out


def csv_rows(path):
    with open(path, "rb") as fh:
        data = fh.read()
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = data.decode("cp1256", errors="replace")
    sample = text[:4096]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
    except csv.Error:
        dialect = csv.excel
    import io
    rows = [[c.strip() for c in r] for r in csv.reader(io.StringIO(text, newline=""), dialect)]
    while rows and not any(rows[-1]):
        rows.pop()
    return rows


IMPORT_FIELDS = ["— Skip —", "Full name", "First name", "Middle name", "Last name", "Display name",
                 "Prefix", "Company", "Department", "Job title", "Mobile phone", "Home phone",
                 "Work phone", "Other phone", "Email", "Work email", "Street", "City",
                 "State / Region", "Postal code", "Country", "Website", "Birthday", "Notes",
                 "Categories", "Photo", "Suffix"]

# (field, keywords) - checked in order, first match wins
_GUESS = [
    (None, [" - label", " - type", "e-mail display", "email display", " - formatted", "po box",
            "file as", "phonetic"]),
    ("Photo", ["photo", "picture", "image", "pic", "صورة", "الصورة"]),
    ("Work email", ["work email", "office email", "business email", "بريد العمل"]),
    ("Email", ["email", "e-mail", "mail", "البريد", "الايميل", "الإيميل"]),
    ("Home phone", ["home phone", "هاتف المنزل", "المنزل"]),
    ("Work phone", ["work phone", "office phone", "business phone", "هاتف العمل", "المكتب", "تلفون العمل"]),
    ("Mobile phone", ["mobile", "cell", "phone", "tel", "whatsapp", "الهاتف", "هاتف", "الجوال",
                      "جوال", "النقال", "نقال", "موبايل", "تلفون", "رقم التواصل"]),
    ("First name", ["first name", "first", "given", "الاسم الأول", "الاسم الاول"]),
    ("Middle name", ["middle", "اسم الأب", "اسم الاب", "الأب", "الاب"]),
    ("Last name", ["last name", "last", "surname", "family", "العائلة", "اللقب", "القبيلة"]),
    ("Prefix", ["prefix", "salutation", "اللقب العلمي"]),
    ("Suffix", ["suffix"]),
    ("Display name", ["display", "nickname", "الاسم المختصر"]),
    ("Department", ["department", "dept", "division", "section", "unit", "القسم", "الإدارة",
                    "الادارة", "الوحدة", "الفرع"]),
    ("Job title", ["job", "title", "position", "rank", "role", "الوظيفة", "المسمى", "الرتبة",
                   "المنصب", "الدرجة"]),
    ("Company", ["company", "organization", "organisation", "employer", "الشركة", "الجهة", "المؤسسة"]),
    ("Birthday", ["birth", "dob", "الميلاد"]),
    ("Postal code", ["postal", "zip", "postcode", "الرمز البريدي"]),
    ("State / Region", ["region", "state", "province", "المحافظة"]),
    ("City", ["city", "town", "area", "المدينة", "المنطقة"]),
    ("Country", ["country", "nation", "الدولة", "البلد"]),
    ("Street", ["address", "street", "العنوان", "الشارع"]),
    ("Website", ["website", "web page", "url", "web", "الموقع"]),
    ("Categories", ["group", "category", "categories", "labels", "المجموعة", "الفئة"]),
    ("Notes", ["note", "remark", "comment", "ملاحظ"]),
    ("Full name", ["name", "full name", "الاسم", "اسم", "الإسم"]),
]


def guess_field(header):
    h = header.strip().casefold()
    if not h:
        return IMPORT_FIELDS[0]
    for field, words in _GUESS:
        for w in words:
            if w in h:
                return field or IMPORT_FIELDS[0]
    return IMPORT_FIELDS[0]


_NAME_JOINERS = {"عبد", "ابو", "أبو", "بو", "بن", "ابن", "abd", "abdul", "abu", "bin", "al", "el"}


def name_words(s):
    """Split a name into words, keeping 'عبد الله', 'بن سعد', 'Al Sabah' etc. together."""
    words, merged, i = s.split(), [], 0
    while i < len(words):
        w = words[i]
        if w.casefold() in _NAME_JOINERS and i + 1 < len(words):
            merged.append(w + " " + words[i + 1])
            i += 2
        else:
            merged.append(w)
            i += 1
    return merged


def first_middle(middle):
    """Only the first middle name (e.g. the father's name)."""
    w = name_words(middle)
    return w[0] if w else ""


def split_full_name(s, one_middle=False):
    merged = name_words(s)
    if not merged:
        return "", "", ""
    if len(merged) == 1:
        return merged[0], "", ""
    if len(merged) == 2:
        return merged[0], "", merged[1]
    middle = merged[1] if one_middle else " ".join(merged[1:-1])
    return merged[0], middle, merged[-1]


def normalize_phone(v, cc):
    v = v.strip()
    if v.endswith(".0") and v[:-2].isdigit():
        v = v[:-2]
    digits = re.sub(r"[\s\-().]", "", v)
    if cc and re.fullmatch(r"\d{8}", digits):
        return cc + digits
    if re.fullmatch(r"00\d{8,}", digits):
        return "+" + digits[2:]
    if cc and re.fullmatch(r"\d{11,12}", digits) and digits.startswith(cc.lstrip("+")):
        return "+" + digits
    return v


def normalize_date(v):
    v = v.strip()
    if re.fullmatch(r"\d{4,5}(\.0+)?", v):
        n = int(float(v))
        if 1 < n < 80000:
            return (datetime.date(1899, 12, 30) + datetime.timedelta(days=n)).isoformat()
    m = re.fullmatch(r"(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})", v)
    if m:
        d, mo, y = map(int, m.groups())
        try:
            return datetime.date(y, mo, d).isoformat()
        except ValueError:
            pass
    m = re.fullmatch(r"(\d{4})[/.\-](\d{1,2})[/.\-](\d{1,2}).*", v)
    if m:
        try:
            return datetime.date(*map(int, m.groups())).isoformat()
        except ValueError:
            pass
    return v


def find_photo_file(value, folders):
    v = value.strip().strip('"')
    if not v:
        return None
    cands = [v] if os.path.isabs(v) else [os.path.join(f, v) for f in folders if f]
    for c in cands:
        if os.path.isfile(c):
            return c
        for ext in (".jpg", ".jpeg", ".png", ".gif", ".JPG", ".JPEG", ".PNG"):
            if os.path.isfile(c + ext):
                return c + ext
    return None


def rows_to_contacts(headers, rows, mapping, cc="", photo_folders=(), one_middle=False):
    """mapping: list of field names (one per column). Returns (contacts, photo_misses).
    one_middle: keep only the first middle name (and build the display name from the parts)."""
    contacts, misses = [], 0
    for row in rows:
        row = list(row) + [""] * (len(mapping) - len(row))
        vals = {}
        for i, field in enumerate(mapping):
            v = row[i] if i < len(row) else ""
            if isinstance(v, (bytes, bytearray)):
                if field != "Photo" or not v:
                    continue
            else:
                v = str(v).strip() if v is not None else ""
            if field != IMPORT_FIELDS[0] and v:
                vals.setdefault(field, []).append((headers[i] if i < len(headers) else "", v))
        if not vals:
            continue
        g = lambda f: " ".join(v for _, v in vals.get(f, []) if isinstance(v, str))
        first, middle, last = g("First name"), g("Middle name"), g("Last name")
        full = g("Full name")
        if full and not (first or last):
            first, m2, last = split_full_name(full, one_middle)
            middle = middle or m2
        if one_middle:
            middle = first_middle(middle)
        notes = vals.get("Notes", [])
        note = "\n".join(("%s: %s" % (h, v) if len(notes) > 1 and h else v) for h, v in notes)
        form = {
            "first": first, "middle": middle, "last": last, "prefix": g("Prefix"), "suffix": g("Suffix"),
            "fn": g("Display name") or ("" if one_middle else full),
            "company": g("Company"), "department": g("Department"), "title": g("Job title"),
            "phones": [], "emails": [], "addresses": [], "urls": [v for _, v in vals.get("Website", [])],
            "bday": normalize_date(g("Birthday")) if g("Birthday") else "", "note": note,
        }
        for field, label in (("Mobile phone", "Mobile"), ("Work phone", "Work"),
                             ("Home phone", "Home"), ("Other phone", "Other")):
            for _, v in vals.get(field, []):
                for part in re.split(r"\s*[/,;|]\s*|\n", v):
                    if part.strip():
                        form["phones"].append((label, normalize_phone(part, cc), None))
        for field, label in (("Email", "Home"), ("Work email", "Work")):
            for _, v in vals.get(field, []):
                form["emails"].append((label, v, None))
        adr = [g("Street"), g("City"), g("State / Region"), g("Postal code"), g("Country")]
        if any(adr):
            form["addresses"].append(("Home",) + tuple(adr) + (None,))
        c = Contact(version="3.0")
        apply_form(c, form)
        if g("Categories"):
            c.props.append(Prop("CATEGORIES", ",".join(v for _, v in vals["Categories"])))
        if "Photo" in vals:
            pv = vals["Photo"][0][1]
            if isinstance(pv, (bytes, bytearray)):
                img = extract_image(bytes(pv))
                if img:
                    set_photo(c, img)
                else:
                    misses += 1
            else:
                pf = find_photo_file(pv, photo_folders)
                if pf:
                    with open(pf, "rb") as fh:
                        set_photo(c, fh.read())
                else:
                    misses += 1
        contacts.append(c)
    return contacts, misses


def set_photo(c, raw):
    kind = "PNG" if raw[:4] == b"\x89PNG" else "GIF" if raw[:3] == b"GIF" else "JPEG"
    b64 = base64.b64encode(raw).decode("ascii")
    c.props = [x for x in c.props if x.name != "PHOTO"]
    if c.version == "4.0":
        c.props.append(Prop("PHOTO", "data:image/%s;base64,%s" % (kind.lower(), b64)))
    else:
        c.props.append(Prop("PHOTO", b64, [["ENCODING", "b"], ["TYPE", kind]]))


def photo_raw(prop):
    if prop is None:
        return None
    v = prop.value
    if v.startswith("data:"):
        if ";base64," not in v[:100]:
            return None
        v = v.split(",", 1)[1]
    elif re.match(r"^[A-Za-z][A-Za-z0-9+.\-]*:", v):
        return None  # a URL, not embedded data
    try:
        return base64.b64decode(v + "=" * (-len(v) % 4))
    except Exception:
        return None


def photo_kind(raw):
    if not raw:
        return None
    if raw[:3] == b"\xff\xd8\xff":
        return "JPEG"
    if raw[:8] == b"\x89PNG\r\n\x1a\n":
        return "PNG"
    if raw[:3] == b"GIF":
        return "GIF"
    return None


# ---- bulk edit ----------------------------------------------------------------

BULK_FIELDS = [("company", "Company"), ("department", "Department"), ("title", "Job title"),
               ("categories", "Groups / categories"), ("city", "City"), ("country", "Country"),
               ("note", "Notes")]


def bulk_apply(contacts, changes, note_append=False, cc="", one_middle=False):
    """changes: {key: new value} for the fields to change ('' clears the field)."""
    for c in contacts:
        f = contact_to_form(c)
        if one_middle and f["middle"]:
            old_auto = compose_name(f["prefix"], f["first"], f["middle"], f["last"], f["suffix"])
            f["middle"] = first_middle(f["middle"])
            if f["fn"].strip() == old_auto.strip():
                f["fn"] = ""
        for key in ("company", "department", "title"):
            if key in changes:
                f[key] = changes[key]
        if "note" in changes:
            if note_append and f["note"].strip() and changes["note"]:
                f["note"] = f["note"].rstrip() + "\n" + changes["note"]
            else:
                f["note"] = changes["note"]
        if "city" in changes or "country" in changes:
            adrs = f["addresses"] or ([("Home", "", "", "", "", "", None)]
                                      if changes.get("city") or changes.get("country") else [])
            new = []
            for a in adrs:
                a = list(a)
                if "city" in changes:
                    a[2] = changes["city"]
                if "country" in changes:
                    a[5] = changes["country"]
                new.append(tuple(a))
            f["addresses"] = new
        if cc:
            f["phones"] = [(lb, normalize_phone(v, cc), p) for lb, v, p in f["phones"]]
        apply_form(c, f)
        if "categories" in changes:
            c.props = [p for p in c.props if p.name != "CATEGORIES"]
            if changes["categories"].strip():
                c.props.append(Prop("CATEGORIES", changes["categories"].strip()))



def extract_image(blob):
    """Find a JPEG/PNG/GIF inside raw bytes (Access 'OLE Object' fields wrap images in a header)."""
    best = None
    for sig in (b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n", b"GIF8"):
        i = blob.find(sig)
        if i >= 0 and (best is None or i < best):
            best = i
    return blob[best:] if best is not None else None


def safe_filename(s):
    s = re.sub(r'[\\/:*?"<>|\r\n\t]+', " ", s).strip(" .")
    return s[:80]


# =============================================================================
#  Export
# =============================================================================

TABLE_HEADERS = ["Display name", "First name", "Middle name", "Last name", "Prefix", "Suffix",
                 "Company", "Department", "Job title", "Mobile", "Home phone", "Work phone",
                 "Other phones", "Email", "Work email", "Other emails", "Street", "City",
                 "State / Region", "Postal code", "Country", "Website", "Birthday", "Notes", "Groups"]


def contact_record(c):
    """Flatten a contact into a dict (keys = TABLE_HEADERS)."""
    f = contact_to_form(c)
    phones = {"Mobile": [], "Home": [], "Work": [], "Other": []}
    for label, value, _ in f["phones"]:
        phones["Mobile" if label == "Mobile" else label if label in ("Home", "Work") else "Other"].append(value)
    emails = {"Home": [], "Work": [], "Other": []}
    for label, value, _ in f["emails"]:
        emails[label if label in emails else "Other"].append(value)
    plain = emails["Home"] + emails["Other"]
    adr = f["addresses"][0] if f["addresses"] else ("", "", "", "", "", "", None)
    J = " / ".join
    return {
        "Display name": c.display_name() if c.display_name() != "(No name)" else "",
        "First name": f["first"], "Middle name": f["middle"], "Last name": f["last"],
        "Prefix": f["prefix"], "Suffix": f["suffix"],
        "Company": f["company"], "Department": f["department"], "Job title": f["title"],
        "Mobile": J(phones["Mobile"]), "Home phone": J(phones["Home"]), "Work phone": J(phones["Work"]),
        "Other phones": J(phones["Other"]),
        "Email": plain[0] if plain else "", "Work email": J(emails["Work"]), "Other emails": J(plain[1:]),
        "Street": adr[1], "City": adr[2], "State / Region": adr[3], "Postal code": adr[4], "Country": adr[5],
        "Website": J(f["urls"]), "Birthday": f["bday"], "Notes": f["note"], "Groups": c.text("CATEGORIES"),
    }


def export_photos(contacts, folder):
    """Save each contact's photo into `folder`; returns {contact id: file name}."""
    os.makedirs(folder, exist_ok=True)
    names, used = {}, set()
    for c in contacts:
        raw = photo_raw(c.get("PHOTO"))
        kind = photo_kind(raw)
        if not raw or not kind:
            continue
        ext = {"JPEG": ".jpg", "PNG": ".png", "GIF": ".gif"}[kind]
        base = safe_filename(c.display_name()) or "photo"
        nm, n = base, 2
        while nm.lower() in used:
            nm = "%s (%d)" % (base, n)
            n += 1
        used.add(nm.lower())
        with open(os.path.join(folder, nm + ext), "wb") as fh:
            fh.write(raw)
        names[id(c)] = nm + ext
    return names


def write_csv_table(path, contacts, photo_names=None):
    headers = TABLE_HEADERS + (["Photo"] if photo_names is not None else [])
    with open(path, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(headers)
        for c in contacts:
            r = contact_record(c)
            row = [r[h] for h in TABLE_HEADERS]
            if photo_names is not None:
                row.append(photo_names.get(id(c), ""))
            w.writerow(row)


def _xml_text(s):
    s = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", str(s))
    return xml_escape(s)


def write_xlsx(path, headers, rows, sheet_name="Contacts", rtl=False):
    """Minimal but proper .xlsx writer (bold header, frozen first row, filters, widths)."""
    def col(i):
        s, i = "", i + 1
        while i:
            i, r = divmod(i - 1, 26)
            s = chr(65 + r) + s
        return s
    widths = [min(60, max(10, len(str(h)) + 2, *(len(str(r[i])) + 2 for r in rows[:500] if i < len(r))))
              for i, h in enumerate(headers)]
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
           '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
           '<sheetViews><sheetView workbookViewId="0"%s><pane ySplit="1" topLeftCell="A2" '
           'activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>' % (' rightToLeft="1"' if rtl else ""),
           '<cols>' + "".join('<col min="%d" max="%d" width="%d" customWidth="1"/>' % (i + 1, i + 1, w)
                              for i, w in enumerate(widths)) + '</cols>', '<sheetData>']
    for ri, r in enumerate([headers] + list(rows), start=1):
        cells = []
        for ci, v in enumerate(r):
            if v is None or v == "":
                continue
            st = ' s="1"' if ri == 1 else ' s="2"'
            cells.append('<c r="%s%d" t="inlineStr"%s><is><t xml:space="preserve">%s</t></is></c>'
                         % (col(ci), ri, st, _xml_text(v)))
        out.append('<row r="%d">%s</row>' % (ri, "".join(cells)))
    out.append('</sheetData>')
    if headers:
        out.append('<autoFilter ref="A1:%s%d"/>' % (col(len(headers) - 1), len(rows) + 1))
    out.append('</worksheet>')
    styles = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
              '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
              '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>'
              '<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>'
              '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
              '<fill><patternFill patternType="solid"><fgColor rgb="FF4F5BD5"/><bgColor indexed="64"/></patternFill></fill></fills>'
              '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
              '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
              '<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
              '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>'
              '<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment wrapText="0"/></xf></cellXfs>'
              '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
    files = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
            '</Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            '</Relationships>',
        "xl/workbook.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>'
            '<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">\'%s\'!$A$1:$%s$%d</definedName></definedNames>'
            '</workbook>' % (_xml_text(sheet_name), _xml_text(sheet_name), col(max(0, len(headers) - 1)), len(rows) + 1),
        "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
            '</Relationships>',
        "xl/worksheets/sheet1.xml": "".join(out),
        "xl/styles.xml": styles,
    }
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data.encode("utf-8"))


def _mostly_arabic(contacts):
    n = sum(1 for c in contacts[:200] if re.search("[؀-ۿ]", c.display_name()))
    return n > len(contacts[:200]) / 2


def write_excel(path, contacts, photo_names=None):
    headers = TABLE_HEADERS + (["Photo"] if photo_names is not None else [])
    rows = []
    for c in contacts:
        r = contact_record(c)
        row = [r[h] for h in TABLE_HEADERS]
        if photo_names is not None:
            row.append(photo_names.get(id(c), ""))
        rows.append(row)
    write_xlsx(path, headers, rows, rtl=_mostly_arabic(contacts))


GOOGLE_PHONE_LABEL = {"Mobile": "Mobile", "Home": "Home", "Work": "Work", "Home fax": "Home Fax",
                      "Work fax": "Work Fax", "Pager": "Pager", "Other": "Other"}


def write_google_csv(path, contacts):
    """CSV in the column layout Google Contacts uses for import/export."""
    recs = []
    maxp = maxe = maxa = maxw = 1
    for c in contacts:
        f = contact_to_form(c)
        recs.append((c, f))
        maxp, maxe = max(maxp, len(f["phones"])), max(maxe, len(f["emails"]))
        maxa, maxw = max(maxa, len(f["addresses"])), max(maxw, len(f["urls"]))
    headers = ["First Name", "Middle Name", "Last Name", "Name Prefix", "Name Suffix", "Nickname",
               "File As", "Organization Name", "Organization Title", "Organization Department",
               "Birthday", "Notes", "Labels"]
    for i in range(1, maxe + 1):
        headers += ["E-mail %d - Label" % i, "E-mail %d - Value" % i]
    for i in range(1, maxp + 1):
        headers += ["Phone %d - Label" % i, "Phone %d - Value" % i]
    for i in range(1, maxa + 1):
        headers += ["Address %d - %s" % (i, x) for x in ("Label", "Formatted", "Street", "City", "PO Box",
                                                       "Region", "Postal Code", "Country", "Extended Address")]
    for i in range(1, maxw + 1):
        headers += ["Website %d - Label" % i, "Website %d - Value" % i]
    with open(path, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(headers)
        for c, f in recs:
            groups = [g.strip() for g in c.text("CATEGORIES").split(",") if g.strip()]
            row = [f["first"], f["middle"], f["last"], f["prefix"], f["suffix"], c.text("NICKNAME"),
                   "", f["company"], f["title"], f["department"], f["bday"], f["note"],
                   " ::: ".join(["* myContacts"] + groups)]
            for i in range(maxe):
                row += list(f["emails"][i][:2]) if i < len(f["emails"]) else ["", ""]
            for i in range(maxp):
                row += [GOOGLE_PHONE_LABEL.get(f["phones"][i][0], "Other"), f["phones"][i][1]] \
                    if i < len(f["phones"]) else ["", ""]
            for i in range(maxa):
                if i < len(f["addresses"]):
                    lb, st, ci, rg, pc, co, _ = f["addresses"][i]
                    fmt = "\n".join(x for x in (st, " ".join(y for y in (ci, rg, pc) if y), co) if x)
                    row += [lb, fmt, st, ci, "", rg, pc, co, ""]
                else:
                    row += [""] * 9
            for i in range(maxw):
                row += ["", f["urls"][i]] if i < len(f["urls"]) else ["", ""]
            w.writerow(row)


OUTLOOK_HEADERS = ["First Name", "Middle Name", "Last Name", "Title", "Suffix", "Company", "Department",
                   "Job Title", "Business Street", "Business City", "Business State", "Business Postal Code",
                   "Business Country/Region", "Home Street", "Home City", "Home State", "Home Postal Code",
                   "Home Country/Region", "Business Fax", "Business Phone", "Business Phone 2", "Home Fax",
                   "Home Phone", "Home Phone 2", "Mobile Phone", "Other Phone", "Pager", "E-mail Address",
                   "E-mail Display Name", "E-mail 2 Address", "E-mail 3 Address", "Birthday", "Notes",
                   "Web Page", "Categories"]


def write_outlook_csv(path, contacts):
    """CSV with the column names Outlook / Microsoft 365 expects."""
    with open(path, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(OUTLOOK_HEADERS)
        for c in contacts:
            f = contact_to_form(c)
            r = dict.fromkeys(OUTLOOK_HEADERS, "")
            r.update({"First Name": f["first"], "Middle Name": f["middle"], "Last Name": f["last"],
                      "Title": f["prefix"], "Suffix": f["suffix"], "Company": f["company"],
                      "Department": f["department"], "Job Title": f["title"], "Notes": f["note"],
                      "Web Page": f["urls"][0] if f["urls"] else "",
                      "Categories": ";".join(g.strip() for g in c.text("CATEGORIES").split(",") if g.strip())})
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}", f["bday"]):
                y, m, d = f["bday"].split("-")
                r["Birthday"] = "%d/%d/%s" % (int(m), int(d), y)
            slots = {"Mobile": ["Mobile Phone"], "Home": ["Home Phone", "Home Phone 2"],
                     "Work": ["Business Phone", "Business Phone 2"], "Home fax": ["Home Fax"],
                     "Work fax": ["Business Fax"], "Pager": ["Pager"], "Other": ["Other Phone"]}
            for label, value, _ in f["phones"]:
                for col in slots.get(label, []) + ["Other Phone"]:
                    if not r[col]:
                        r[col] = value
                        break
            for i, (label, value, _) in enumerate(f["emails"][:3]):
                r[["E-mail Address", "E-mail 2 Address", "E-mail 3 Address"][i]] = value
            if f["emails"]:
                r["E-mail Display Name"] = "%s (%s)" % (c.display_name(), f["emails"][0][1])
            for label, st, ci, rg, pc, co, _ in f["addresses"]:
                pre = "Business" if label == "Work" else "Home"
                if not r[pre + " Street"]:
                    r.update({pre + " Street": st, pre + " City": ci, pre + " State": rg,
                              pre + " Postal Code": pc, pre + " Country/Region": co})
            w.writerow([r[h] for h in OUTLOOK_HEADERS])


def write_split(folder, contacts, version=None):
    used = set()
    for c in contacts:
        base = safe_filename(c.display_name()) or "contact"
        name, n = base, 2
        while name.lower() in used or os.path.exists(os.path.join(folder, name + ".vcf")):
            name = "%s (%d)" % (base, n)
            n += 1
        used.add(name.lower())
        write_vcf_file(os.path.join(folder, name + ".vcf"), [c], version)


# =============================================================================
#  Microsoft Access (.accdb / .mdb)
# =============================================================================

ACCESS_DRIVER = "Microsoft Access Driver (*.mdb, *.accdb)"


class AccessError(Exception):
    pass


def _access_conn(path):
    import pyodbc
    if ACCESS_DRIVER not in pyodbc.drivers():
        raise AccessError(
            "The Microsoft Access database driver (64-bit) is not installed on this PC.\n\n"
            "Install \"Microsoft Access Database Engine 2016 Redistributable\" (64-bit) from "
            "Microsoft, or 64-bit Microsoft Office/Access.")
    return pyodbc.connect("DRIVER={%s};DBQ=%s;" % (ACCESS_DRIVER, path), autocommit=True)


def _cell(v):
    if v is None:
        return ""
    if isinstance(v, (bytes, bytearray, memoryview)):
        return bytes(v)
    if isinstance(v, datetime.datetime):
        return v.date().isoformat() if not (v.hour or v.minute or v.second) else v.isoformat(" ")
    if isinstance(v, datetime.date):
        return v.isoformat()
    if isinstance(v, float) and v.is_integer() and abs(v) < 1e15:
        return str(int(v))
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    return str(v).strip()


def access_tables(path):
    """Return [(table name, loader)] where loader() gives rows (first row = column names).
    Uses the Access ODBC driver when available, otherwise a built-in reader."""
    try:
        conn = _access_conn(path)
        cur = conn.cursor()
        names = [t.table_name for t in cur.tables(tableType="TABLE")]

        def make(n):
            def load():
                c2 = conn.cursor()
                c2.execute("SELECT * FROM [%s]" % n.replace("]", "]]"))
                rows = [[d[0] for d in c2.description]]
                rows += [[_cell(v) for v in r] for r in c2.fetchall()]
                return rows
            return load
        return [(n, make(n)) for n in names]
    except Exception as first_error:
        try:
            from access_parser import AccessParser
        except ImportError:
            raise AccessError(str(first_error))
        db = AccessParser(path)
        names = [n for n in db.catalog if not n.startswith(("MSys", "~", "f_"))]

        def make2(n):
            def load():
                t = db.parse_table(n)
                cols = list(t.keys())
                n_rows = max((len(v) for v in t.values()), default=0)
                rows = [cols]
                for i in range(n_rows):
                    rows.append([_cell(t[col][i] if i < len(t[col]) else None) for col in cols])
                return rows
            return load
        return [(n, make2(n)) for n in names]


def create_access_db(path):
    if os.path.exists(path):
        os.remove(path)
    if IS_WIN:
        import ctypes
        cfg = ctypes.windll.ODBCCP32.SQLConfigDataSourceW
        for key in ("CREATE_DBV12", "CREATE_DB") if path.lower().endswith(".accdb") else ("CREATE_DBV4", "CREATE_DB"):
            attrs = '%s="%s" General\0\0' % (key, path)
            if cfg(None, 1, ACCESS_DRIVER, attrs) and os.path.exists(path):
                return
        ps = ("$c = New-Object -ComObject ADOX.Catalog; "
              "$c.Create('Provider=Microsoft.ACE.OLEDB.12.0;Data Source=%s')" % path.replace("'", "''"))
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True,
                       creationflags=0x08000000)
    if not os.path.exists(path):
        raise AccessError("Could not create the Access file. The Microsoft Access database engine "
                          "(64-bit) needs to be installed on this PC.")


def write_access(path, contacts, table="Contacts"):
    create_access_db(path)
    conn = _access_conn(path)
    cur = conn.cursor()
    cols = TABLE_HEADERS
    memo = {"Notes", "Other phones", "Other emails", "Street", "Website", "Groups"}
    ddl = ", ".join("[%s] %s" % (h, "LONGTEXT" if h in memo else "VARCHAR(255)") for h in cols)
    cur.execute("CREATE TABLE [%s] ([ID] COUNTER PRIMARY KEY, %s, [Photo] LONGBINARY)" % (table, ddl))
    sql = "INSERT INTO [%s] (%s, [Photo]) VALUES (%s)" % (
        table, ", ".join("[%s]" % h for h in cols), ", ".join("?" * (len(cols) + 1)))
    import pyodbc
    for c in contacts:
        r = contact_record(c)
        vals = [(r[h] or None) if h in memo else ((r[h][:255]) or None) for h in cols]
        raw = photo_raw(c.get("PHOTO"))
        vals.append(pyodbc.Binary(raw) if raw else None)
        cur.execute(sql, vals)
    conn.close()
