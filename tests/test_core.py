import csv
import os

import vcf_core as v
from conftest import DATA

ANDROID_21 = (
    "BEGIN:VCARD\r\nVERSION:2.1\r\n"
    "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D8=A7=D9=84=D8=B9=D9=84=D9=8A;=D9=85=D8=AD=D9=85=D8=AF;;;\r\n"
    "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D9=85=D8=AD=D9=85=D8=AF =D8=A7=D9=84=D8=B9=D9=84=D9=8A\r\n"
    "TEL;CELL;PREF:+96599887766\r\n"
    "NOTE;ENCODING=QUOTED-PRINTABLE:line one=0D=0Aline two with a soft =\r\nbreak\r\n"
    "X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;Mo;1;;;;;;;;;;;;;\r\n"
    "END:VCARD\r\n"
)

IPHONE_30 = (
    "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Appleseed;John;;;\r\nFN:John Appleseed\r\n"
    "ORG:Example Inc.;Engineering;\r\nitem1.TEL;type=pref:+1 408 555 0100\r\n"
    "item1.X-ABLabel:_$!<Mobile>!$_\r\nEMAIL;type=INTERNET;type=HOME:john@example.com\r\n"
    "ADR;type=HOME:;;1 Main St\\, Bldg 2;Springfield;CA;95014;USA\r\n"
    "NOTE:semicolon\; comma\\, and\\nnewline\r\nEND:VCARD\r\n"
)


def test_android_quoted_printable_arabic():
    c = v.parse_vcf(ANDROID_21)[0]
    assert c.display_name() == "محمد العلي"
    assert c.name_parts()[:2] == ["العلي", "محمد"]
    assert c.text("NOTE").replace("\r", "") == "line one\nline two with a soft break"


def test_iphone_labels_and_escaping():
    c = v.parse_vcf(IPHONE_30)[0]
    f = v.contact_to_form(c)
    assert f["phones"][0][0] == "Mobile"           # from X-ABLabel
    assert f["addresses"][0][1] == "1 Main St, Bldg 2"
    assert c.text("NOTE") == "semicolon; comma, and\nnewline"


def test_unknown_fields_survive_editing():
    c = v.parse_vcf(ANDROID_21)[0]
    f = v.contact_to_form(c)
    f["first"] = "أحمد"
    v.apply_form(c, f)
    out = v.serialize_contact(c)
    assert "X-ANDROID-CUSTOM:" in out and "أحمد" in out


def test_round_trip_every_version(tmp_path):
    cs = v.read_vcf_file(os.path.join(DATA, "demo.vcf")) + v.parse_vcf(ANDROID_21 + IPHONE_30)
    for ver in ("2.1", "3.0", "4.0"):
        p = tmp_path / ("out_%s.vcf" % ver)
        v.write_vcf_file(str(p), cs, ver)
        back = v.read_vcf_file(str(p))
        assert [b.display_name() for b in back] == [a.display_name() for a in cs]
        assert sum(b.has_photo() for b in back) == sum(a.has_photo() for a in cs)


def test_google_csv_has_bom_for_excel(tmp_path):
    p = tmp_path / "google.csv"
    v.write_google_csv(str(p), v.parse_vcf(ANDROID_21))
    assert p.read_bytes()[:3] == b"\xef\xbb\xbf"
    rows = list(csv.reader(open(p, encoding="utf-8-sig")))
    assert rows[1][0] == "محمد"


def test_google_and_outlook_csv_reimport(tmp_path):
    cs = v.read_vcf_file(os.path.join(DATA, "demo.vcf"))
    for writer in (v.write_google_csv, v.write_outlook_csv):
        p = tmp_path / "x.csv"
        writer(str(p), cs)
        rows = v.csv_rows(str(p))
        back, _ = v.rows_to_contacts(rows[0], rows[1:], [v.guess_field(h) for h in rows[0]])
        assert len(back) == len(cs)
        for b in back:  # label columns must not become phone numbers
            assert not any(t.value in ("Mobile", "Home", "Work", "Other") for t in b.all("TEL"))


def test_excel_round_trip_with_photos(tmp_path):
    cs = v.read_vcf_file(os.path.join(DATA, "demo.vcf"))
    names = v.export_photos(cs, str(tmp_path / "photos"))
    x = tmp_path / "c.xlsx"
    v.write_excel(str(x), cs, names)
    (name, rows), = v.xlsx_sheets(str(x))
    back, misses = v.rows_to_contacts(rows[0], rows[1:], [v.guess_field(h) for h in rows[0]],
                                      photo_folders=[str(tmp_path / "photos")])
    assert misses == 0
    assert [b.display_name() for b in back] == [c.display_name() for c in cs]
    assert sum(b.has_photo() for b in back) == sum(c.has_photo() for c in cs)


def test_csv_cells_with_line_breaks(tmp_path):
    p = tmp_path / "m.csv"
    with open(p, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(["الاسم", "ملاحظات", "الهاتف"])
        w.writerow(["خالد العوض", "سطر1\nسطر2", "99887766"])
        w.writerow(["سارة", "x", "55443322"])
    rows = v.csv_rows(str(p))
    assert len(rows) == 3 and rows[1][1] == "سطر1\nسطر2"


def test_column_guessing():
    g = v.guess_field
    assert g("الاسم") == "Full name" and g("الرتبة") == "Job title" and g("القسم") == "Department"
    assert g("رقم الهاتف") == "Mobile phone" and g("الرقم العسكري") == v.IMPORT_FIELDS[0]
    assert g("Phone 1 - Label") == v.IMPORT_FIELDS[0] and g("Organization Title") == "Job title"
    assert g("Address 1 - City") == "City" and g("Business Phone") == "Work phone"


def test_middle_name_options():
    assert v.split_full_name("محمد مختار حسن أحمد الشطي") == ("محمد", "مختار حسن أحمد", "الشطي")
    assert v.split_full_name("محمد مختار حسن أحمد الشطي", one_middle=True) == ("محمد", "مختار", "الشطي")
    assert v.split_full_name("عبد الله محمد عبد الرحمن العلي", True) == ("عبد الله", "محمد", "العلي")
    rows = [["محمد مختار حسن أحمد الشطي", "99887766"]]
    (c,), _ = v.rows_to_contacts(["الاسم", "الهاتف"], rows, ["Full name", "Mobile phone"], "+965", one_middle=True)
    assert c.display_name() == "محمد مختار الشطي" and c.text("TEL") == "+96599887766"
    v.bulk_apply([v.parse_vcf(ANDROID_21)[0]], {}, one_middle=True)


def test_phone_and_date_normalising():
    assert v.normalize_phone("99887766", "+965") == "+96599887766"
    assert v.normalize_phone("0096599887766", "+965") == "+96599887766"
    assert v.normalize_date("15/03/1980") == "1980-03-15"
    assert v.normalize_date("32875") == "1990-01-02"
