import csv
import os

import vcf_core as v
from conftest import DATA

ANDROID_21 = (
    "BEGIN:VCARD\r\nVERSION:2.1\r\n"
    "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D8=A7=D8=B3=D9=852;=D8=A7=D8=B3=D9=851;;;\r\n"
    "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D8=A7=D8=B3=D9=851 =D8=A7=D8=B3=D9=852\r\n"
    "TEL;CELL;PREF:+96599999999\r\n"
    "NOTE;ENCODING=QUOTED-PRINTABLE:line one=0D=0Aline two with a soft =\r\nbreak\r\n"
    "X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;Mo;1;;;;;;;;;;;;;\r\n"
    "END:VCARD\r\n"
)

IPHONE_30 = (
    "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Name;Name;;;\r\nFN:Name Name\r\n"
    "ORG:Company;Department;\r\nitem1.TEL;type=pref:55555555\r\n"
    "item1.X-ABLabel:_$!<Mobile>!$_\r\nEMAIL;type=INTERNET;type=HOME:name@example.com\r\n"
    "ADR;type=HOME:;;Street\\, 2;City;Region;00000;Country\r\n"
    "NOTE:semicolon\; comma\\, and\\nnewline\r\nEND:VCARD\r\n"
)


def test_android_quoted_printable_arabic():
    c = v.parse_vcf(ANDROID_21)[0]
    assert c.display_name() == "اسم1 اسم2"
    assert c.name_parts()[:2] == ["اسم2", "اسم1"]
    assert c.text("NOTE").replace("\r", "") == "line one\nline two with a soft break"


def test_iphone_labels_and_escaping():
    c = v.parse_vcf(IPHONE_30)[0]
    f = v.contact_to_form(c)
    assert f["phones"][0][0] == "Mobile"           # from X-ABLabel
    assert f["addresses"][0][1] == "Street, 2"
    assert c.text("NOTE") == "semicolon; comma, and\nnewline"


def test_unknown_fields_survive_editing():
    c = v.parse_vcf(ANDROID_21)[0]
    f = v.contact_to_form(c)
    f["first"] = "اسم3"
    v.apply_form(c, f)
    out = v.serialize_contact(c)
    assert "X-ANDROID-CUSTOM:" in out and "اسم3" in out


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
    assert rows[1][0] == "اسم1"


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
        w.writerow(["Name", "سطر1\nسطر2", "99999999"])
        w.writerow(["Name", "x", "66666666"])
    rows = v.csv_rows(str(p))
    assert len(rows) == 3 and rows[1][1] == "سطر1\nسطر2"


def test_column_guessing():
    g = v.guess_field
    assert g("الاسم") == "Full name" and g("الرتبة") == "Job title" and g("القسم") == "Department"
    assert g("رقم الهاتف") == "Mobile phone" and g("الرقم العسكري") == v.IMPORT_FIELDS[0]
    assert g("Phone 1 - Label") == v.IMPORT_FIELDS[0] and g("Organization Title") == "Job title"
    assert g("Address 1 - City") == "City" and g("Business Phone") == "Work phone"


def test_middle_name_options():
    assert v.split_full_name("Name1 Name2 Name3 Name4 Name5") == ("Name1", "Name2 Name3 Name4", "Name5")
    assert v.split_full_name("Name1 Name2 Name3 Name4 Name5", one_middle=True) == ("Name1", "Name2", "Name5")
    assert v.split_full_name("عبد Name1 Name2 عبد Name3 Name4", True) == ("عبد Name1", "Name2", "Name4")
    rows = [["Name1 Name2 Name3 Name4 Name5", "99999999"]]
    (c,), _ = v.rows_to_contacts(["الاسم", "الهاتف"], rows, ["Full name", "Mobile phone"], "+965", one_middle=True)
    assert c.display_name() == "Name1 Name2 Name5" and c.text("TEL") == "+96599999999"
    v.bulk_apply([v.parse_vcf(ANDROID_21)[0]], {}, one_middle=True)


def test_phone_and_date_normalising():
    assert v.normalize_phone("55555555", "+965") == "+96555555555"
    assert v.normalize_phone("0096566666666", "+965") == "+96566666666"
    assert v.normalize_date("15/03/1980") == "1980-03-15"
    assert v.normalize_date("32875") == "1990-01-02"
