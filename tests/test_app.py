import importlib.machinery
import importlib.util
import os

import pytest
from conftest import DATA, ROOT

QtWidgets = pytest.importorskip("PySide6.QtWidgets")


@pytest.fixture(scope="module")
def app_mod():
    loader = importlib.machinery.SourceFileLoader("vcf_desk_app", os.path.join(ROOT, "VCF_Desk.pyw"))
    spec = importlib.util.spec_from_loader("vcf_desk_app", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    app = QtWidgets.QApplication.instance() or QtWidgets.QApplication([])
    mod.Theme.setup(app)
    mod.ask = lambda *a, **k: 0
    mod.info = lambda *a, **k: None
    return mod


def test_open_edit_undo_and_bulk(app_mod):
    from PySide6.QtCore import Qt
    from PySide6.QtTest import QTest
    w = app_mod.MainWindow([])
    w.show()
    w.open_paths([os.path.join(DATA, "demo.vcf")], replace=True)
    assert len(w.contacts) == 10
    c = w.contacts[0]
    w.select([c])
    e = w.form["fields"]["first"]
    old = e.text()
    e.setFocus()
    e.selectAll()
    QTest.keyClicks(e, "Test")
    w.commit()
    assert c.display_name().startswith("Test")
    QTest.keyClick(e, Qt.Key_Z, Qt.ControlModifier)
    w.commit()
    assert e.text() == old
    w.list.selectAll()
    assert len(w.selected()) == 10
    chk, ed = w.bulk["department"]
    chk.setChecked(True)
    ed.setText("Ops")
    w.apply_bulk(w.selected())
    assert all(app_mod.contact_to_form(x)["department"] == "Ops" for x in w.contacts)
    w.dirty = False
    w.close()


def test_dialogs_open(app_mod, tmp_path):
    w = app_mod.MainWindow([])
    w.open_paths([os.path.join(DATA, "demo.vcf")], replace=True)
    d = app_mod.ExportDialog(w)
    d.show()
    d.close()
    import vcf_core as v
    x = tmp_path / "s.xlsx"
    v.write_excel(str(x), w.contacts)
    imp = app_mod.ImportDialog(w, str(x))
    assert imp.ok and len(imp.maps) == len(v.TABLE_HEADERS)
    w.dirty = False
    w.close()
