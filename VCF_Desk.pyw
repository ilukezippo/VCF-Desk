#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VCF Desk - Contacts Manager.  A free, modern vCard (.vcf) editor for Windows.

UI: PySide6 (Qt).  All file-format logic lives in vcf_core.py.
"""

import os
import re
import sys
import tempfile

from PySide6.QtCore import (QAbstractListModel, QBuffer, QByteArray, QEvent, QIODevice,
                            QItemSelection, QItemSelectionModel, QModelIndex, QObject, QPoint,
                            QRect, QRectF, QRunnable, QSize, Qt, QThreadPool, QTimer, Signal)
from PySide6.QtGui import (QAction, QColor, QFont, QFontDatabase, QFontMetrics, QIcon, QImage,
                           QImageReader, QKeySequence, QPainter, QPainterPath, QPen, QPixmap,
                           QShortcut, QSyntaxHighlighter, QTextCharFormat)
from PySide6.QtWidgets import (QAbstractItemView, QApplication, QButtonGroup, QCheckBox, QComboBox,
                               QDialog, QFileDialog, QFrame, QGridLayout, QHBoxLayout, QLabel,
                               QLineEdit, QListView, QListWidget, QListWidgetItem, QMainWindow,
                               QMenu, QMessageBox, QPlainTextEdit, QPushButton, QRadioButton,
                               QScrollArea, QSizePolicy, QStackedWidget, QStyle, QStyledItemDelegate,
                               QTabBar, QToolButton, QVBoxLayout, QWidget)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vcf_core as core  # noqa: E402
from vcf_core import (BULK_FIELDS, IMPORT_FIELDS, LABELS, Contact, Prop, apply_form,  # noqa: E402
                      bulk_apply, compose_name, contact_to_form, csv_rows, duplicate_key,
                      guess_field, parse_vcf, photo_kind, photo_raw, read_vcf_file,
                      rows_to_contacts, safe_filename, serialize_contact, set_photo,
                      write_vcf_file, xlsx_sheets)

APP_NAME = "VCF Desk"
APP_TAGLINE = "Contacts Manager"
APP_VERSION = "1.0"
IS_WIN = sys.platform.startswith("win")

BG = "#0e1014"
PANEL = "#14171d"
CARD = "#1a1e26"
FIELD = "#20252f"
BORDER = "#2a303c"
HOVER = "#1f2430"
SEL = "#283252"
TEXT = "#e7eaf0"
MUTED = "#8b93a5"
FAINT = "#5c6475"
ACCENT = "#7b8cff"
ACCENT_H = "#91a0ff"
DANGER = "#ff6b6b"
OK = "#4cd4a0"
INK = "#0b0d12"
AVATAR_COLORS = ["#7b8cff", "#4cd4a0", "#ff9f6b", "#e879c7", "#5cc8ff", "#f5c451",
                 "#a78bfa", "#ff6b8b", "#34d3c4", "#9bd35c"]

SHEET_EXT = (".xlsx", ".xlsm", ".csv")
ACCESS_EXT = (".accdb", ".mdb")
VCF_EXT = (".vcf", ".vcard")

GLYPH = {"open": "\uE8E5", "save": "\uE74E", "add": "\uE710", "delete": "\uE74D",
         "more": "\uE712", "search": "\uE721", "copy": "\uE8C8", "import": "\uE8B5",
         "export": "\uEDE1", "close": "\uE711", "edit": "\uE70F", "photo": "\uEB9F",
         "view": "\uE890", "table": "\uE80A", "phone": "\uE8EA", "contact": "\uE77B",
         "mail": "\uE715", "folder": "\uE8B7", "db": "\uE1D3", "globe": "\uE774"}

_ARABIC = re.compile("[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")


def initials(name):
    words = [w for w in re.split(r"\s+", name.strip()) if w and w[0].isalnum()]
    if not words:
        return "?"
    if _ARABIC.search(words[0]):
        return words[0][:1]
    if len(words) == 1:
        return words[0][:1].upper()
    return (words[0][:1] + words[-1][:1]).upper()


def avatar_color(name):
    return AVATAR_COLORS[sum(ord(ch) for ch in name) % len(AVATAR_COLORS)]


# =============================================================================
#  Look & feel
# =============================================================================

class Theme:
    icon_font = None
    ui_family = "Segoe UI"

    @classmethod
    def setup(cls, app):
        fams = set(QFontDatabase.families())
        cls.ui_family = next((f for f in ("Segoe UI", "Tahoma", "Inter", "DejaVu Sans") if f in fams), "Sans")
        icon_fam = next((f for f in ("Segoe Fluent Icons", "Segoe MDL2 Assets") if f in fams), None)
        cls.icon_font = QFont(icon_fam) if icon_fam else None
        f = QFont(cls.ui_family, 10)
        f.setWeight(QFont.Weight.Bold)
        app.setFont(f)
        tmp = os.path.join(tempfile.gettempdir(), "vcfdesk_ui")
        os.makedirs(tmp, exist_ok=True)
        check = os.path.join(tmp, "check.png").replace("\\", "/")
        r_on = os.path.join(tmp, "radio_on.png").replace("\\", "/")
        r_off = os.path.join(tmp, "radio_off.png").replace("\\", "/")
        cls._draw_radio(r_on, True)
        cls._draw_radio(r_off, False)
        arrow = os.path.join(tmp, "arrow.png").replace("\\", "/")
        cls._draw_check(check)
        cls._draw_arrow(arrow)
        app.setStyleSheet(STYLE.replace("@CHECK@", check).replace("@ARROW@", arrow)
                          .replace("@FONT@", cls.ui_family).replace("@RON@", r_on).replace("@ROFF@", r_off))

    @staticmethod
    def _draw_radio(path, on):
        pm = QPixmap(36, 36)
        pm.fill(Qt.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        if on:
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(ACCENT))
            p.drawEllipse(QRectF(1, 1, 34, 34))
            p.setBrush(QColor(INK))
            p.drawEllipse(QRectF(11, 11, 14, 14))
        else:
            p.setPen(QPen(QColor(FAINT), 2))
            p.setBrush(QColor(FIELD))
            p.drawEllipse(QRectF(2, 2, 32, 32))
        p.end()
        pm.save(path)

    @staticmethod
    def _draw_check(path):
        pm = QPixmap(32, 32)
        pm.fill(Qt.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        pen = QPen(QColor(INK), 4.5)
        pen.setCapStyle(Qt.RoundCap)
        pen.setJoinStyle(Qt.RoundJoin)
        p.setPen(pen)
        p.drawPolyline([QPoint(8, 17), QPoint(14, 23), QPoint(25, 10)])
        p.end()
        pm.save(path)

    @staticmethod
    def _draw_arrow(path):
        pm = QPixmap(24, 24)
        pm.fill(Qt.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        pen = QPen(QColor(MUTED), 2.4)
        pen.setCapStyle(Qt.RoundCap)
        p.setPen(pen)
        p.drawPolyline([QPoint(6, 9), QPoint(12, 15), QPoint(18, 9)])
        p.end()
        pm.save(path)


def icon(name, color=TEXT, size=16):
    if Theme.icon_font is None or name not in GLYPH:
        return QIcon()
    dpr = QApplication.instance().devicePixelRatio() if QApplication.instance() else 1
    pm = QPixmap(int(size * 2 * dpr), int(size * 2 * dpr))
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    f = QFont(Theme.icon_font)
    f.setPixelSize(int(size * 1.25 * dpr))
    p.setFont(f)
    p.setPen(QColor(color))
    p.drawText(pm.rect(), Qt.AlignCenter, GLYPH[name])
    p.end()
    pm.setDevicePixelRatio(dpr * 2)
    return QIcon(pm)


STYLE = """
* { font-family: "@FONT@"; font-weight: 700; color: %(TEXT)s; }
QMainWindow, QDialog, #Main { background: %(BG)s; }
QWidget#TopBar { background: %(PANEL)s; border-bottom: 1px solid %(BORDER)s; }
QWidget#Side { background: %(PANEL)s; border-right: 1px solid %(BORDER)s; }
QWidget#Status { background: %(PANEL)s; border-top: 1px solid %(BORDER)s; }
QWidget#DialogFoot { background: %(PANEL)s; border-top: 1px solid %(BORDER)s; }
QLabel { background: transparent; }
QLabel[role="muted"] { color: %(MUTED)s; }
QLabel[role="faint"] { color: %(FAINT)s; }
QLabel[role="title"] { font-size: 19pt; }
QLabel[role="h2"] { font-size: 13pt; }
QLabel[role="brand"] { font-size: 13pt; }
QLabel[role="tagline"] { color: %(MUTED)s; font-size: 8pt; }
QLabel[role="section"] { color: %(MUTED)s; font-size: 8pt; letter-spacing: 1px; }
QLabel[role="ok"] { color: %(OK)s; }
QLabel[role="danger"] { color: %(DANGER)s; }
QFrame#Card { background: %(CARD)s; border: 1px solid %(BORDER)s; border-radius: 10px; }
QFrame#Card QLabel { background: transparent; }
QLineEdit, QPlainTextEdit, QComboBox {
    background: %(FIELD)s; border: 1px solid %(BORDER)s; border-radius: 6px; padding: 6px 8px;
    selection-background-color: %(SEL)s; selection-color: %(TEXT)s; }
QLineEdit:focus, QPlainTextEdit:focus, QComboBox:focus { border: 1px solid %(ACCENT)s; }
QLineEdit:disabled { background: %(CARD)s; color: %(FAINT)s; }
QLineEdit[search="true"] { padding-left: 4px; }
QComboBox { padding-right: 24px; }
QComboBox::drop-down { border: none; width: 24px; }
QComboBox::down-arrow { image: url(@ARROW@); width: 14px; height: 14px; }
QComboBox QAbstractItemView { background: %(FIELD)s; border: 1px solid %(BORDER)s; outline: 0;
    selection-background-color: %(SEL)s; padding: 4px; }
QPushButton { background: %(CARD)s; border: 1px solid %(BORDER)s; border-radius: 6px; padding: 7px 14px; }
QPushButton:hover { background: %(HOVER)s; border-color: %(FAINT)s; }
QPushButton:pressed { background: %(SEL)s; }
QPushButton[kind="primary"] { background: %(ACCENT)s; color: %(INK)s; border: none; }
QPushButton[kind="primary"]:hover { background: %(ACCENT_H)s; }
QPushButton[kind="danger"] { color: %(DANGER)s; }
QPushButton[kind="danger"]:hover { background: #3a1f24; }
QPushButton[kind="ghost"] { background: transparent; border: none; }
QPushButton[kind="ghost"]:hover { background: %(HOVER)s; }
QPushButton[kind="link"] { background: transparent; border: none; color: %(ACCENT)s; padding: 4px 2px; }
QPushButton[kind="link"]:hover { color: %(ACCENT_H)s; }
QPushButton::menu-indicator { image: none; width: 0; }
QToolButton#Remove { background: transparent; border: none; color: %(FAINT)s; padding: 4px 8px; font-size: 12pt; }
QToolButton#Remove:hover { color: %(DANGER)s; }
QScrollArea { border: none; background: transparent; }
QScrollArea > QWidget > QWidget { background: transparent; }
QScrollBar:vertical { background: transparent; width: 10px; margin: 2px; }
QScrollBar::handle:vertical { background: %(BORDER)s; border-radius: 3px; min-height: 30px; }
QScrollBar::handle:vertical:hover { background: %(FAINT)s; }
QScrollBar::add-line, QScrollBar::sub-line { height: 0; width: 0; }
QScrollBar::add-page, QScrollBar::sub-page { background: none; }
QScrollBar:horizontal { background: transparent; height: 10px; margin: 2px; }
QScrollBar::handle:horizontal { background: %(BORDER)s; border-radius: 3px; min-width: 30px; }
QListView { background: %(PANEL)s; border: none; outline: 0; }
QMenu { background: %(CARD)s; border: 1px solid %(BORDER)s; padding: 5px; }
QMenu::item { padding: 7px 28px 7px 12px; border-radius: 5px; }
QMenu::item:selected { background: %(SEL)s; }
QMenu::item:disabled { color: %(FAINT)s; }
QMenu::separator { height: 1px; background: %(BORDER)s; margin: 5px 8px; }
QCheckBox, QRadioButton { spacing: 9px; background: transparent; }
QCheckBox::indicator { width: 17px; height: 17px; border-radius: 5px; border: 1px solid %(FAINT)s; background: %(FIELD)s; }
QCheckBox::indicator:checked { background: %(ACCENT)s; border-color: %(ACCENT)s; image: url(@CHECK@); }
QRadioButton::indicator { width: 18px; height: 18px; border: none; background: transparent; image: url(@ROFF@); }
QRadioButton::indicator:checked { image: url(@RON@); }
QRadioButton:disabled { color: %(FAINT)s; }
QTabBar { background: transparent; }
QTabBar::tab { background: transparent; color: %(MUTED)s; padding: 8px 2px; margin-right: 24px;
    border: none; border-bottom: 2px solid transparent; }
QTabBar::tab:selected { color: %(TEXT)s; border-bottom: 2px solid %(ACCENT)s; }
QTabBar::tab:hover { color: %(TEXT)s; }
QToolTip { background: %(CARD)s; color: %(TEXT)s; border: 1px solid %(BORDER)s; padding: 4px; }
QListWidget#Formats { background: transparent; border: none; outline: 0; }
QListWidget#Formats::item { background: %(CARD)s; border: 1px solid %(BORDER)s; border-radius: 8px;
    padding: 0; margin: 0 0 6px 0; color: %(TEXT)s; }
QListWidget#Formats::item:hover { border-color: %(FAINT)s; }
QListWidget#Formats::item:selected { background: %(SEL)s; border-color: %(ACCENT)s; color: %(TEXT)s; }
QMessageBox QLabel { color: %(TEXT)s; }
""" % dict(BG=BG, PANEL=PANEL, CARD=CARD, FIELD=FIELD, BORDER=BORDER, HOVER=HOVER, SEL=SEL,
           TEXT=TEXT, MUTED=MUTED, FAINT=FAINT, ACCENT=ACCENT, ACCENT_H=ACCENT_H, DANGER=DANGER,
           OK=OK, INK=INK)


def label(text="", role=None, wrap=False):
    lb = QLabel(text)
    lb.setAlignment(Qt.AlignLeft | Qt.AlignVCenter | Qt.AlignAbsolute)
    if role:
        lb.setProperty("role", role)
    if wrap:
        lb.setWordWrap(True)
    return lb


def button(text="", slot=None, kind=None, icon_name=None):
    b = QPushButton(text)
    if kind:
        b.setProperty("kind", kind)
    if icon_name:
        col = INK if kind == "primary" else DANGER if kind == "danger" else ACCENT if kind == "link" else TEXT
        b.setIcon(icon(icon_name, col))
    b.setCursor(Qt.PointingHandCursor)
    if slot:
        b.clicked.connect(lambda checked=False: slot())
    return b


def card(title=None):
    fr = QFrame()
    fr.setObjectName("Card")
    lay = QVBoxLayout(fr)
    lay.setContentsMargins(18, 14, 18, 16)
    lay.setSpacing(8)
    if title:
        lay.addWidget(label(title.upper(), "section"))
    return fr, lay


def circle_pixmap(img, size, dpr):
    """Crop a QImage to a centred square and clip it to an anti-aliased circle."""
    px = int(size * dpr)
    w, h = img.width(), img.height()
    side = min(w, h)
    sq = img.copy((w - side) // 2, (h - side) // 2, side, side).scaled(
        px, px, Qt.IgnoreAspectRatio, Qt.SmoothTransformation)
    out = QPixmap(px, px)
    out.fill(Qt.transparent)
    p = QPainter(out)
    p.setRenderHint(QPainter.Antialiasing)
    p.setRenderHint(QPainter.SmoothPixmapTransform)
    path = QPainterPath()
    path.addEllipse(QRectF(0, 0, px, px))
    p.setClipPath(path)
    p.drawImage(0, 0, sq)
    p.end()
    out.setDevicePixelRatio(dpr)
    return out


def initials_pixmap(name, size, dpr, font_px=None):
    px = int(size * dpr)
    out = QPixmap(px, px)
    out.fill(Qt.transparent)
    p = QPainter(out)
    p.setRenderHint(QPainter.Antialiasing)
    p.setPen(Qt.NoPen)
    p.setBrush(QColor(avatar_color(name)))
    p.drawEllipse(QRectF(0, 0, px, px))
    f = QFont(Theme.ui_family)
    f.setWeight(QFont.Weight.Bold)
    f.setPixelSize(int((font_px or size * 0.38) * dpr))
    p.setFont(f)
    p.setPen(QColor(INK))
    p.drawText(QRect(0, 0, px, px), Qt.AlignCenter, initials(name))
    p.end()
    out.setDevicePixelRatio(dpr)
    return out


def app_icon_pixmap(size=64):
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setPen(Qt.NoPen)
    p.setBrush(QColor(ACCENT))
    p.drawRoundedRect(QRectF(0, 0, size, size), size * 0.24, size * 0.24)
    p.setBrush(QColor(INK))
    s = size / 28.0
    p.drawEllipse(QRectF(10 * s, 5.5 * s, 8 * s, 8 * s))
    path = QPainterPath()
    path.moveTo(5.5 * s, 23 * s)
    path.cubicTo(5.5 * s, 15 * s, 22.5 * s, 15 * s, 22.5 * s, 23 * s)
    path.closeSubpath()
    p.drawPath(path)
    p.end()
    return pm


# =============================================================================
#  Photo thumbnails (decoded in background threads - the UI never waits)
# =============================================================================

def decode_image(raw, min_side=0):
    """QImage from bytes; when min_side is given, let the decoder scale down while
    reading (very fast for JPEG)."""
    buf = QBuffer()
    buf.setData(QByteArray(raw))
    buf.open(QIODevice.ReadOnly)
    rd = QImageReader(buf)
    rd.setAutoTransform(True)
    sz = rd.size()
    if min_side and sz.isValid() and min(sz.width(), sz.height()) > min_side * 1.5:
        k = min_side / min(sz.width(), sz.height())
        rd.setScaledSize(QSize(max(1, round(sz.width() * k)), max(1, round(sz.height() * k))))
    img = rd.read()
    buf.close()
    return img


class _ThumbSignals(QObject):
    done = Signal(object, QImage)


class _ThumbJob(QRunnable):
    def __init__(self, key, raw, px, signals):
        super().__init__()
        self.key, self.raw, self.px, self.signals = key, raw, px, signals

    def run(self):
        try:
            img = decode_image(self.raw, self.px)
        except Exception:
            img = QImage()
        self.signals.done.emit(self.key, img)


class Thumbs(QObject):
    updated = Signal(object)

    def __init__(self):
        super().__init__()
        self.cache = {}
        self.pending = set()
        self.pool = QThreadPool()
        self.pool.setMaxThreadCount(max(2, min(4, QThreadPool.globalInstance().maxThreadCount())))
        self.sig = _ThumbSignals()
        self.sig.done.connect(self._done)

    @staticmethod
    def key(prop, size):
        return (hash(prop.value), len(prop.value), size)

    def get(self, prop, size):
        """Circular QPixmap, or None while it is loading / when it can't be decoded."""
        if prop is None:
            return None
        k = self.key(prop, size)
        got = self.cache.get(k)
        if got is not None:
            return got or None
        if k not in self.pending:
            raw = photo_raw(prop)
            if not raw:
                self.cache[k] = False
                return None
            self.pending.add(k)
            dpr = QApplication.instance().devicePixelRatio()
            self.pool.start(_ThumbJob(k, raw, int(size * dpr), self.sig))
        return None

    def _done(self, key, img):
        self.pending.discard(key)
        if img.isNull():
            self.cache[key] = False
        else:
            self.cache[key] = circle_pixmap(img, key[2], QApplication.instance().devicePixelRatio())
        self.updated.emit(key)


# =============================================================================
#  Contact list
# =============================================================================

class ContactModel(QAbstractListModel):
    ContactRole = Qt.UserRole + 1

    def __init__(self):
        super().__init__()
        self.items = []

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self.items)

    def data(self, index, role=Qt.DisplayRole):
        if not index.isValid():
            return None
        c = self.items[index.row()]
        if role == Qt.DisplayRole:
            return c.display_name()
        if role == self.ContactRole:
            return c
        return None

    def set_items(self, items):
        self.beginResetModel()
        self.items = items
        self.endResetModel()

    def refresh(self, c=None):
        if not self.items:
            return
        if c is None:
            self.dataChanged.emit(self.index(0), self.index(len(self.items) - 1))
        elif c in self.items:
            i = self.index(self.items.index(c))
            self.dataChanged.emit(i, i)


class ContactDelegate(QStyledItemDelegate):
    ROW = 58

    def __init__(self, app):
        super().__init__()
        self.app = app

    def sizeHint(self, option, index):
        return QSize(200, self.ROW)

    def paint(self, p, option, index):
        c = index.data(ContactModel.ContactRole)
        r = option.rect
        p.save()
        p.setRenderHint(QPainter.Antialiasing)
        selected = bool(option.state & QStyle.State_Selected)
        hover = bool(option.state & QStyle.State_MouseOver)
        box = QRectF(r.adjusted(8, 3, -8, -3))
        if selected or hover:
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(SEL if selected else HOVER))
            p.drawRoundedRect(box, 8, 8)
        if selected:
            p.setBrush(QColor(ACCENT))
            p.drawRoundedRect(QRectF(r.left() + 8, r.top() + 15, 3, r.height() - 30), 1.5, 1.5)
        name = c.display_name()
        size = 36
        ax, ay = r.left() + 20, r.top() + (r.height() - size) // 2
        pm = self.app.thumbs.get(c.get("PHOTO"), size) if c.has_photo() else None
        if pm is None:
            pm = self.app.initials_pm(name, size)
        p.drawPixmap(ax, ay, pm)
        tx = ax + size + 12
        tw = r.right() - tx - 14
        f = QFont(option.font)
        f.setWeight(QFont.Weight.Bold)
        p.setFont(f)
        fm = QFontMetrics(f)
        sub = c.subtitle()
        p.setPen(QColor(TEXT))
        if sub:
            p.drawText(QRect(tx, r.top() + 9, tw, 22), Qt.AlignLeft | Qt.AlignVCenter,
                       fm.elidedText(name, Qt.ElideRight, tw))
            f2 = QFont(f)
            f2.setPointSizeF(f.pointSizeF() - 1)
            p.setFont(f2)
            p.setPen(QColor(MUTED))
            p.drawText(QRect(tx, r.top() + 30, tw, 20), Qt.AlignLeft | Qt.AlignVCenter,
                       QFontMetrics(f2).elidedText(sub, Qt.ElideRight, tw))
        else:
            p.drawText(QRect(tx, r.top(), tw, r.height()), Qt.AlignLeft | Qt.AlignVCenter,
                       fm.elidedText(name, Qt.ElideRight, tw))
        p.restore()


class ContactList(QListView):
    def __init__(self, app):
        super().__init__()
        self.app = app
        self.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.setUniformItemSizes(True)
        self.setMouseTracking(True)
        self.setVerticalScrollMode(QAbstractItemView.ScrollPerPixel)
        self.verticalScrollBar().setSingleStep(24)
        self.setContextMenuPolicy(Qt.CustomContextMenu)
        self.customContextMenuRequested.connect(self._menu)

    def _menu(self, pos):
        idx = self.indexAt(pos)
        if idx.isValid() and not self.selectionModel().isSelected(idx):
            self.selectionModel().select(idx, QItemSelectionModel.ClearAndSelect)
        if self.selectionModel().selectedIndexes():
            self.app.context_menu.exec(self.viewport().mapToGlobal(pos))

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Delete:
            self.app.delete_selected()
            return
        super().keyPressEvent(e)


# =============================================================================
#  Small widgets
# =============================================================================

class Combo(QComboBox):
    """Combo box that doesn't change value when the page is scrolled over it."""

    def __init__(self, *a):
        super().__init__(*a)
        self.setFocusPolicy(Qt.StrongFocus)

    def wheelEvent(self, e):
        e.ignore()  # let the scroll area scroll; pick values by clicking


class ClickLabel(QLabel):
    clicked = Signal()

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit()
        super().mousePressEvent(e)


class VCardHighlighter(QSyntaxHighlighter):
    def __init__(self, doc):
        super().__init__(doc)
        self.key = QTextCharFormat()
        self.key.setForeground(QColor(ACCENT))
        self.param = QTextCharFormat()
        self.param.setForeground(QColor(MUTED))

    def highlightBlock(self, text):
        if not text or text[0] in " \t":
            return
        inq, colon = False, -1
        for i, ch in enumerate(text):
            if ch == '"':
                inq = not inq
            elif ch == ":" and not inq:
                colon = i
                break
        if colon < 0:
            return
        semi = text.find(";", 0, colon)
        kend = semi if semi >= 0 else colon
        self.setFormat(0, kend, self.key)
        if semi >= 0:
            self.setFormat(semi, colon - semi, self.param)


def scroll_wrap(widget):
    sa = QScrollArea()
    sa.setWidgetResizable(True)
    sa.setFrameShape(QFrame.NoFrame)
    sa.setWidget(widget)
    sa.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
    return sa


def center_on_screen(win):
    scr = win.screen() or QApplication.primaryScreen()
    g = scr.availableGeometry()
    fg = win.frameGeometry()
    fg.moveCenter(g.center())
    win.move(fg.topLeft())


def dark_title_bar(win):
    if not IS_WIN:
        return
    try:
        import ctypes
        hwnd = int(win.winId())
        val = ctypes.c_int(1)
        for attr in (20, 19):
            if ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(val), 4) == 0:
                break
    except Exception:
        pass


class Dialog(QDialog):
    """Dark dialog, centred over its parent."""

    def __init__(self, parent, title):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setWindowFlag(Qt.WindowContextHelpButtonHint, False)

    def showEvent(self, e):
        super().showEvent(e)
        dark_title_bar(self)
        if self.parent() is not None:
            pg = self.parent().window().frameGeometry()
            fg = self.frameGeometry()
            fg.moveCenter(pg.center())
            self.move(fg.topLeft())


def ask(parent, title, text, buttons=("Yes", "No"), default=0, danger=False):
    """Simple themed question box. Returns the index of the clicked button, or -1."""
    dlg = Dialog(parent, title)
    lay = QVBoxLayout(dlg)
    lay.setContentsMargins(24, 22, 24, 18)
    lay.setSpacing(16)
    t = label(text, wrap=True)
    t.setMinimumWidth(360)
    t.setMaximumWidth(520)
    lay.addWidget(t)
    row = QHBoxLayout()
    row.addStretch(1)
    result = [-1]
    for i, b in enumerate(buttons):
        kind = ("danger" if danger else "primary") if i == default else None
        btn = button(b, kind=kind)
        btn.clicked.connect(lambda checked=False, i=i: (result.__setitem__(0, i), dlg.accept()))
        row.addWidget(btn)
        if i == default:
            btn.setDefault(True)
            btn.setFocus()
    lay.addLayout(row)
    dlg.exec()
    return result[0]


def info(parent, title, text):
    ask(parent, title, text, buttons=("OK",))


# =============================================================================
#  Main window
# =============================================================================

class MainWindow(QMainWindow):
    def __init__(self, files):
        super().__init__()
        self.contacts = []
        self.path = None
        self.dirty = False
        self.current = None
        self.form = None
        self.form_dirty = False
        self.mode = "details"
        self._ipm = {}
        self.thumbs = Thumbs()
        self.thumbs.updated.connect(self._thumb_ready)
        self.commit_timer = QTimer(self)
        self.commit_timer.setSingleShot(True)
        self.commit_timer.setInterval(350)
        self.commit_timer.timeout.connect(self.commit)
        self.search_timer = QTimer(self)
        self.search_timer.setSingleShot(True)
        self.search_timer.setInterval(120)
        self.search_timer.timeout.connect(self.refresh_list)

        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(QIcon(app_icon_pixmap(64)))
        self.setAcceptDrops(True)
        self.resize(1240, 800)
        self.setMinimumSize(900, 560)
        self._build()
        self._actions()
        self.refresh_list()
        self.show_empty()
        QApplication.instance().installEventFilter(self)
        if files:
            QTimer.singleShot(50, lambda: self.open_paths(files, replace=True))

    @staticmethod
    def _dropped_files(md):
        if md is None or not md.hasUrls():
            return []
        return [u.toLocalFile() for u in md.urls() if u.isLocalFile()
                and u.toLocalFile().lower().endswith(VCF_EXT + SHEET_EXT + ACCESS_EXT)]

    def eventFilter(self, obj, ev):
        try:
            t = ev.type()
            if t in (QEvent.DragEnter, QEvent.DragMove, QEvent.Drop) and isinstance(obj, QWidget) \
                    and obj.window() is self:
                files = self._dropped_files(ev.mimeData())
                if files:
                    if t == QEvent.Drop:
                        QTimer.singleShot(0, lambda: self.on_drop(files))
                    ev.acceptProposedAction()
                    return True
        except RuntimeError:
            pass
        return False

    # ------------------------------------------------------------------ layout
    def _build(self):
        root = QWidget()
        root.setObjectName("Main")
        self.setCentralWidget(root)
        v = QVBoxLayout(root)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(0)

        top = QWidget()
        top.setObjectName("TopBar")
        top.setAttribute(Qt.WA_StyledBackground, True)
        top.setFixedHeight(64)
        th = QHBoxLayout(top)
        th.setContentsMargins(18, 0, 14, 0)
        th.setSpacing(8)
        logo = QLabel()
        lp = app_icon_pixmap(64).scaled(34, 34, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        logo.setPixmap(lp)
        th.addWidget(logo)
        brand = QVBoxLayout()
        brand.setSpacing(0)
        brand.setContentsMargins(4, 0, 0, 0)
        brand.addStretch(1)
        brand.addWidget(label(APP_NAME, "brand"))
        brand.addWidget(label(APP_TAGLINE, "tagline"))
        brand.addStretch(1)
        th.addLayout(brand)
        self.file_label = label("", "muted")
        th.addSpacing(18)
        th.addWidget(self.file_label)
        th.addStretch(1)
        th.addWidget(button("Open", self.cmd_open, "ghost", "open"))
        th.addWidget(button("Import", self.cmd_import, "ghost", "import"))
        th.addWidget(button("Export", self.cmd_export, "ghost", "export"))
        th.addSpacing(6)
        th.addWidget(button("Save", self.cmd_save, "primary", "save"))
        self.more_btn = button("" if Theme.icon_font else "•••", None, "ghost", "more")
        th.addWidget(self.more_btn)
        v.addWidget(top)

        body = QHBoxLayout()
        body.setSpacing(0)
        body.setContentsMargins(0, 0, 0, 0)
        v.addLayout(body, 1)

        side = QWidget()
        side.setObjectName("Side")
        side.setAttribute(Qt.WA_StyledBackground, True)
        side.setFixedWidth(340)
        sv = QVBoxLayout(side)
        sv.setContentsMargins(14, 14, 14, 12)
        sv.setSpacing(6)
        self.search = QLineEdit()
        self.search.setProperty("search", True)
        self.search.setPlaceholderText("Search name, phone, email…")
        self.search.setClearButtonEnabled(True)
        if Theme.icon_font:
            self.search.addAction(icon("search", MUTED), QLineEdit.LeadingPosition)
        self.search.textChanged.connect(lambda *_: self.search_timer.start())
        sv.addWidget(self.search)
        self.count_label = label("", "section")
        self.count_label.setContentsMargins(4, 6, 0, 2)
        sv.addWidget(self.count_label)
        self.model = ContactModel()
        self.list = ContactList(self)
        self.list.setModel(self.model)
        self.list.setItemDelegate(ContactDelegate(self))
        self.list.selectionModel().selectionChanged.connect(lambda *_: self.on_selection())
        sv.addWidget(self.list, 1)
        sv.addWidget(button("New contact", self.cmd_new_contact, None, "add"))
        body.addWidget(side)

        self.main = QStackedWidget()
        body.addWidget(self.main, 1)

        st = QWidget()
        st.setObjectName("Status")
        st.setAttribute(Qt.WA_StyledBackground, True)
        st.setFixedHeight(30)
        sh = QHBoxLayout(st)
        sh.setContentsMargins(16, 0, 16, 0)
        self.status_left = label("", "muted")
        self.status_right = label("", "ok")
        sh.addWidget(self.status_left, 1)
        sh.addWidget(self.status_right)
        v.addWidget(st)
        self.flash_timer = QTimer(self)
        self.flash_timer.setSingleShot(True)
        self.flash_timer.timeout.connect(lambda: self.status_right.setText(""))

    def _actions(self):
        def act(text, slot, keys=None, ic=None):
            a = QAction(text, self)
            if ic:
                a.setIcon(icon(ic))
            if keys:
                a.setShortcut(QKeySequence(keys))
                a.setShortcutContext(Qt.WindowShortcut)
            a.triggered.connect(lambda checked=False: slot())
            self.addAction(a)
            return a

        self.a_open = act("Open…", self.cmd_open, "Ctrl+O", "open")
        self.a_save = act("Save", self.cmd_save, "Ctrl+S", "save")
        self.a_save_as = act("Save as…", self.cmd_save_as, "Ctrl+Shift+S")
        self.a_new = act("New contact", self.cmd_new_contact, "Ctrl+N", "add")
        self.a_new_file = act("New file", self.cmd_new_file, "Ctrl+Shift+N")
        self.a_import = act("Import (vCard, Excel, CSV, Access)…", self.cmd_import, "Ctrl+I", "import")
        self.a_export = act("Export…", self.cmd_export, "Ctrl+E", "export")
        self.a_dup = act("Duplicate", self.cmd_duplicate, "Ctrl+D", "copy")
        self.a_find = act("Find", lambda: (self.search.setFocus(), self.search.selectAll()), "Ctrl+F")
        self.a_all = act("Select all contacts", self.cmd_select_all, None)
        self.a_sort = act("Sort A → Z", self.cmd_sort)
        self.a_dedupe = act("Remove exact duplicates", self.cmd_dedupe)
        self.a_about = act("About", self.cmd_about)
        self.a_delete = QAction("Delete", self)
        self.a_delete.setIcon(icon("delete", DANGER))
        self.a_delete.triggered.connect(self.delete_selected)

        m = QMenu(self)
        for a in (self.a_new_file, self.a_save_as, None, self.a_import, self.a_export, None,
                  self.a_all, self.a_sort, self.a_dedupe, None, self.a_about):
            m.addSeparator() if a is None else m.addAction(a)
        self.more_btn.setMenu(m)
        cm = QMenu(self)
        for a in (self.a_dup, self.a_export, None, self.a_delete):
            cm.addSeparator() if a is None else cm.addAction(a)
        self.context_menu = cm

    def showEvent(self, e):
        super().showEvent(e)
        if not getattr(self, "_shown", False):
            self._shown = True
            center_on_screen(self)
            dark_title_bar(self)

    # ------------------------------------------------------------------ helpers
    def initials_pm(self, name, size):
        k = (name, size)
        pm = self._ipm.get(k)
        if pm is None:
            if len(self._ipm) > 3000:
                self._ipm.clear()
            pm = initials_pixmap(name, size, self.devicePixelRatioF())
            self._ipm[k] = pm
        return pm

    def avatar_for(self, c, size):
        if c.has_photo():
            pm = self.thumbs.get(c.get("PHOTO"), size)
            if pm is not None:
                return pm
        return self.initials_pm(c.display_name() if c.display_name() != "(No name)" else "?", size)

    def _thumb_ready(self, key):
        self.list.viewport().update()
        if key[2] == 56 and getattr(self, "multi_avatar", None) is not None:
            try:
                self.paint_multi_avatars()
            except RuntimeError:
                self.multi_avatar = None
        if self.current is not None and self.current.has_photo():
            k = Thumbs.key(self.current.get("PHOTO"), key[2])
            if k == key:
                self.update_header()
                pm = self.thumbs.cache.get(key)
                if pm and getattr(self, "photo_label", None) is not None and key[2] == 96:
                    try:
                        self.photo_label.setPixmap(pm)
                    except RuntimeError:
                        pass

    def flash(self, msg, color=OK):
        self.status_right.setText(msg)
        self.status_right.setStyleSheet("color: %s;" % color)
        self.flash_timer.start(5000)

    def set_dirty(self, v=True):
        self.dirty = v
        self.update_title()

    def update_title(self):
        name = os.path.basename(self.path) if self.path else ("Untitled.vcf" if self.contacts else "")
        star = " •" if self.dirty else ""
        self.setWindowTitle("%s%s — %s" % (name, star, APP_NAME) if name else "%s — %s" % (APP_NAME, APP_TAGLINE))
        self.file_label.setText(name + star)
        bits = ["%d contacts" % len(self.contacts)]
        if self.path:
            bits.append(self.path)
        self.status_left.setText("   ·   ".join(bits))

    # ------------------------------------------------------------ list / state
    def selected(self):
        rows = sorted({i.row() for i in self.list.selectionModel().selectedIndexes()})
        return [self.model.items[r] for r in rows if r < len(self.model.items)]

    def select(self, contacts, notify=True):
        sm = self.list.selectionModel()
        sel = QItemSelection()
        last = None
        for c in contacts:
            if c in self.model.items:
                idx = self.model.index(self.model.items.index(c))
                sel.select(idx, idx)
                last = idx
        if not notify:
            sm.blockSignals(True)
        sm.select(sel, QItemSelectionModel.ClearAndSelect)
        if last is not None:
            sm.setCurrentIndex(last, QItemSelectionModel.NoUpdate)
            self.list.scrollTo(last)
        if not notify:
            sm.blockSignals(False)
            self.list.viewport().update()

    def refresh_list(self):
        keep = self.selected()
        q = self.search.text().strip().casefold()
        if q:
            qd = re.sub(r"\D", "", q)
            items = [c for c in self.contacts
                     if q in c.search_blob() or (len(qd) >= 3 and qd in re.sub(r"\D", "", c.search_blob()))]
        else:
            items = list(self.contacts)
        self.model.set_items(items)
        self.select([c for c in keep if c in items], notify=False)
        total = len(self.contacts)
        self.count_label.setText(("%d OF %d CONTACTS" % (len(items), total)) if q else
                                 "%d CONTACT%s" % (total, "" if total == 1 else "S"))
        self.update_title()

    def on_selection(self):
        sel = self.selected()
        if len(sel) == 1:
            if sel[0] is not self.current:
                self.show_editor(sel[0])
        elif len(sel) > 1:
            self.show_multi(sel)
        else:
            self.show_empty()

    # -------------------------------------------------------------- main pages
    def set_page(self, w):
        self.commit()
        old = self.main.currentWidget()
        self.main.addWidget(w)
        self.main.setCurrentWidget(w)
        if old is not None:
            self.main.removeWidget(old)
            old.deleteLater()
        self.photo_label = None
        self.multi_avatar = None

    def show_empty(self):
        self.commit()
        self.current = None
        self.form = None
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.addStretch(2)
        ic = QLabel()
        ic.setPixmap(app_icon_pixmap(72))
        ic.setAlignment(Qt.AlignCenter)
        lay.addWidget(ic)
        lay.addSpacing(14)
        if self.contacts:
            t, s = "Select a contact", "Pick someone from the list, or create a new contact."
        else:
            t = "Open a vCard file"
            s = ("Open or drop a .vcf file here — exports from Android, iPhone, Google and Outlook all work.\n"
                 "You can also import contacts from Excel, CSV or an Access database.")
        tl = label(t, "title")
        tl.setAlignment(Qt.AlignCenter)
        sl = label(s, "muted")
        sl.setAlignment(Qt.AlignCenter)
        lay.addWidget(tl)
        lay.addWidget(sl)
        lay.addSpacing(18)
        row = QHBoxLayout()
        row.addStretch(1)
        if not self.contacts:
            row.addWidget(button("Open .vcf file", self.cmd_open, "primary", "open"))
            row.addWidget(button("Import Excel / Access", self.cmd_import, None, "table"))
        row.addWidget(button("New contact", self.cmd_new_contact, None, "add"))
        row.addStretch(1)
        lay.addLayout(row)
        lay.addStretch(3)
        self.set_page(page)

    # ----------------------------------------------------------------- editor
    def show_editor(self, c, mode=None):
        self.commit()
        if mode:
            self.mode = mode
        self.current = c
        self._pending_photo_label = None
        page = QWidget()
        pv = QVBoxLayout(page)
        pv.setContentsMargins(32, 24, 32, 0)
        pv.setSpacing(0)

        head = QHBoxLayout()
        self.avatar = ClickLabel()
        self.avatar.setFixedSize(80, 80)
        self.avatar.clicked.connect(self.show_photo_preview)
        head.addWidget(self.avatar)
        head.addSpacing(16)
        ht = QVBoxLayout()
        ht.setSpacing(2)
        ht.addStretch(1)
        self.head_name = label("", "title", wrap=True)
        self.head_name.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.head_name.setMinimumWidth(120)
        self.head_sub = label("", "muted")
        ht.addWidget(self.head_name)
        ht.addWidget(self.head_sub)
        ht.addStretch(1)
        head.addLayout(ht, 1)
        head.addWidget(button("Duplicate", self.cmd_duplicate, None, "copy"))
        head.addWidget(button("Delete", self.delete_selected, "danger", "delete"))
        pv.addLayout(head)
        pv.addSpacing(12)

        tabs = QTabBar()
        tabs.setDrawBase(False)
        tabs.setExpanding(False)
        tabs.addTab("Details")
        tabs.addTab("Raw vCard")
        tabs.setCurrentIndex(1 if self.mode == "raw" else 0)
        tabs.currentChanged.connect(lambda i: self.switch_mode("raw" if i == 1 else "details"))
        pv.addWidget(tabs)
        line = QFrame()
        line.setFixedHeight(1)
        line.setStyleSheet("background: %s;" % BORDER)
        pv.addWidget(line)

        if self.mode == "raw":
            self.form = None
            pv.addWidget(self._build_raw(c), 1)
        else:
            pv.addWidget(self._build_details(c), 1)
        self.set_page(page)
        self.photo_label = getattr(self, "_pending_photo_label", None)
        self.update_header()

    def switch_mode(self, mode):
        if self.current is not None and mode != self.mode:
            c = self.current
            QTimer.singleShot(0, lambda: self.show_editor(c, mode))

    def update_header(self):
        c = self.current
        if c is None:
            return
        try:
            if self.form is not None:
                fv = {k: w.text() for k, w in self.form["fields"].items()}
                auto = compose_name(fv["prefix"], fv["first"], fv["middle"], fv["last"], fv["suffix"])
                name = fv["fn"].strip() or auto or fv["company"].strip() or "New contact"
                sub = " · ".join(x for x in (fv["title"].strip(), fv["company"].strip()) if x)
            else:
                name = c.display_name()
                org = c.get("ORG")
                orgname = org.value[0] if org is not None and isinstance(org.value, list) and org.value else ""
                sub = " · ".join(x for x in (c.text("TITLE"), orgname) if x)
            ver = "vCard 2.1 → saves as 3.0" if c.version == "2.1" else "vCard " + c.version
            self.head_name.setText(name)
            self.head_sub.setText(sub + ("   ·   " if sub else "") + ver)
            pm = self.thumbs.get(c.get("PHOTO"), 80) if c.has_photo() else None
            self.avatar.setPixmap(pm if pm is not None else self.initials_pm(name, 80))
            self.avatar.setCursor(Qt.PointingHandCursor if c.has_photo() else Qt.ArrowCursor)
        except RuntimeError:
            pass  # widgets already replaced

    def _field(self, grid, row, col, text, value, key=None, hint=None, span=1):
        box = QVBoxLayout()
        box.setSpacing(4)
        top = QHBoxLayout()
        top.addWidget(label(text, "muted"))
        top.addStretch(1)
        if hint:
            top.addWidget(label(hint, "faint"))
        box.addLayout(top)
        e = QLineEdit(value)
        e.textEdited.connect(self.mark_form_dirty)
        box.addWidget(e)
        grid.addLayout(box, row, col, 1, span)
        if key:
            self.form["fields"][key] = e
        return e

    def _grid(self, lay, cols):
        g = QGridLayout()
        g.setHorizontalSpacing(12)
        g.setVerticalSpacing(10)
        for i in range(cols):
            g.setColumnStretch(i, 1)
        lay.addLayout(g)
        return g

    def _build_details(self, c):
        data = contact_to_form(c)
        self.form = {"fields": {}, "phones": [], "emails": [], "addresses": [], "urls": [], "note": None}
        body = QWidget()
        bv = QVBoxLayout(body)
        bv.setContentsMargins(0, 14, 10, 30)
        bv.setSpacing(14)

        fr, lay = card("Name")
        g = self._grid(lay, 2)
        first = self._field(g, 0, 0, "First name", data["first"], "first")
        self._field(g, 0, 1, "Last name", data["last"], "last")
        g = self._grid(lay, 3)
        self._field(g, 0, 0, "Middle name", data["middle"], "middle")
        self._field(g, 0, 1, "Prefix", data["prefix"], "prefix")
        self._field(g, 0, 2, "Suffix", data["suffix"], "suffix")
        g = self._grid(lay, 1)
        self._field(g, 0, 0, "Display name", data["fn"], "fn", hint="leave empty to build it from the name")
        bv.addWidget(fr)

        fr, lay = card("Work")
        g = self._grid(lay, 2)
        self._field(g, 0, 0, "Company", data["company"], "company")
        self._field(g, 0, 1, "Department", data["department"], "department")
        self._field(g, 1, 0, "Job title", data["title"], "title", span=2)
        bv.addWidget(fr)

        fr, lay = card("Phone numbers")
        self.phone_box = QVBoxLayout()
        self.phone_box.setSpacing(8)
        lay.addLayout(self.phone_box)
        for lb, val, prop in data["phones"]:
            self._typed_row("phones", self.phone_box, "TEL", lb, val, prop)
        lay.addWidget(button("Add phone", lambda: self._typed_row(
            "phones", self.phone_box, "TEL", "Mobile", "", None, True), "link", "add"), 0, Qt.AlignLeft)
        bv.addWidget(fr)

        fr, lay = card("Email")
        self.email_box = QVBoxLayout()
        self.email_box.setSpacing(8)
        lay.addLayout(self.email_box)
        for lb, val, prop in data["emails"]:
            self._typed_row("emails", self.email_box, "EMAIL", lb, val, prop)
        lay.addWidget(button("Add email", lambda: self._typed_row(
            "emails", self.email_box, "EMAIL", "Home", "", None, True), "link", "add"), 0, Qt.AlignLeft)
        bv.addWidget(fr)

        fr, lay = card("Addresses")
        self.adr_box = QVBoxLayout()
        self.adr_box.setSpacing(14)
        lay.addLayout(self.adr_box)
        for a in data["addresses"]:
            self._address(a)
        lay.addWidget(button("Add address", lambda: self._address(
            ("Home", "", "", "", "", "", None), True), "link", "add"), 0, Qt.AlignLeft)
        bv.addWidget(fr)

        fr, lay = card("Websites")
        self.url_box = QVBoxLayout()
        self.url_box.setSpacing(8)
        lay.addLayout(self.url_box)
        for u in data["urls"]:
            self._url_row(u)
        lay.addWidget(button("Add website", lambda: self._url_row("", True), "link", "add"), 0, Qt.AlignLeft)
        bv.addWidget(fr)

        fr, lay = card("More")
        g = self._grid(lay, 2)
        self._field(g, 0, 0, "Birthday", data["bday"], "bday", hint="YYYY-MM-DD")
        lay.addWidget(label("Notes", "muted"))
        note = QPlainTextEdit()
        note.setPlainText(data["note"])
        note.setFixedHeight(96)
        note.textChanged.connect(self.mark_form_dirty)
        lay.addWidget(note)
        self.form["note"] = note
        bv.addWidget(fr)

        bv.addWidget(self._photo_card(c))

        fr, lay = card("Other fields")
        extra = [p for p in c.props if p.name not in core.MULTI_EDIT | core.SINGLE_EDIT | {"PHOTO"}]
        names = []
        for p in extra:
            if p.name not in names:
                names.append(p.name)
        txt = ("%d other field%s kept untouched: %s" % (
            len(extra), "" if len(extra) == 1 else "s", ", ".join(names[:8]) + ("…" if len(names) > 8 else ""))
            if extra else "No other fields.")
        lay.addWidget(label(txt + "\nEdit them in the Raw vCard tab.", "muted", wrap=True))
        bv.addWidget(fr)
        bv.addStretch(1)

        for k in ("first", "last", "fn", "company", "title", "middle", "prefix", "suffix"):
            self.form["fields"][k].textEdited.connect(lambda *_: self.update_header())
        self.form_dirty = False
        if not any(data[k] for k in ("first", "last", "fn", "company")) and not data["phones"]:
            QTimer.singleShot(60, first.setFocus)
        return scroll_wrap(body)

    def _photo_card(self, c):
        fr, lay = card("Photo")
        row = QHBoxLayout()
        row.setSpacing(16)
        lay.addLayout(row)
        ph = c.get("PHOTO")
        raw = photo_raw(ph)
        kind = photo_kind(raw)
        self._pending_photo_label = None
        if ph is None:
            row.addWidget(label("No photo", "muted"))
            row.addStretch(1)
            row.addWidget(button("Add photo…", self.cmd_set_photo, None, "photo"))
            return fr
        pl = ClickLabel()
        pl.setFixedSize(96, 96)
        pl.setCursor(Qt.PointingHandCursor)
        pm = self.thumbs.get(ph, 96)
        pl.setPixmap(pm if pm is not None else self.initials_pm(c.display_name(), 96))
        pl.clicked.connect(self.show_photo_preview)
        self._pending_photo_label = pl
        row.addWidget(pl)
        col = QVBoxLayout()
        col.addStretch(1)
        col.addWidget(label(self._photo_info(raw, kind)))
        btns = QHBoxLayout()
        btns.setSpacing(6)
        if raw:
            btns.addWidget(button("View", self.show_photo_preview, None, "view"))
        btns.addWidget(button("Change…", self.cmd_set_photo, None, "photo"))
        if raw:
            btns.addWidget(button("Save image…", self.cmd_save_photo, None, "save"))
        btns.addWidget(button("Remove", self.cmd_remove_photo, "danger", "delete"))
        btns.addStretch(1)
        col.addLayout(btns)
        col.addStretch(1)
        row.addLayout(col, 1)
        return fr

    @staticmethod
    def _photo_info(raw, kind):
        if not raw:
            return "Linked photo (not embedded in the file)"
        buf = QBuffer()
        buf.setData(QByteArray(raw))
        buf.open(QIODevice.ReadOnly)
        sz = QImageReader(buf).size()
        buf.close()
        dims = "%d × %d" % (sz.width(), sz.height()) if sz.isValid() else ""
        return " · ".join(x for x in (kind or "Image", dims, "%d KB" % max(1, len(raw) // 1024)) if x)

    def _remove_btn(self, cb):
        b = QToolButton()
        b.setObjectName("Remove")
        b.setText("✕")
        b.setCursor(Qt.PointingHandCursor)
        b.setToolTip("Remove")
        b.clicked.connect(cb)
        return b

    def _combo(self, values, current):
        cb = Combo()
        cb.addItems(values)
        if current in values:
            cb.setCurrentText(current)
        cb.setFixedWidth(118)
        cb.currentIndexChanged.connect(self.mark_form_dirty)
        return cb

    def _typed_row(self, key, box, prop_name, lb, value, prop, focus=False):
        w = QWidget()
        h = QHBoxLayout(w)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(8)
        cb = self._combo(LABELS[prop_name], lb)
        e = QLineEdit(value)
        e.textEdited.connect(self.mark_form_dirty)
        h.addWidget(cb)
        h.addWidget(e, 1)
        rec = {"label": cb, "value": e, "prop": prop, "widget": w}

        def remove():
            self.form[key].remove(rec)
            w.deleteLater()
            self.mark_form_dirty()

        h.addWidget(self._remove_btn(remove))
        box.addWidget(w)
        self.form[key].append(rec)
        if focus:
            e.setFocus()
            self.mark_form_dirty()

    def _address(self, a, focus=False):
        lb, street, city, region, postal, country, prop = a
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(8)
        top = QHBoxLayout()
        cb = self._combo(LABELS["ADR"], lb)
        top.addWidget(cb)
        top.addStretch(1)
        rec = {"label": cb, "prop": prop, "widget": w}

        def remove():
            self.form["addresses"].remove(rec)
            w.deleteLater()
            self.mark_form_dirty()

        top.addWidget(self._remove_btn(remove))
        v.addLayout(top)
        g = QGridLayout()
        g.setHorizontalSpacing(12)
        g.setVerticalSpacing(10)
        g.setColumnStretch(0, 1)
        g.setColumnStretch(1, 1)
        saved = self.form["fields"]
        self.form["fields"] = {}
        rec["street"] = self._field(g, 0, 0, "Street", street, span=2)
        rec["city"] = self._field(g, 1, 0, "City", city)
        rec["region"] = self._field(g, 1, 1, "State / Region", region)
        rec["postal"] = self._field(g, 2, 0, "Postal code", postal)
        rec["country"] = self._field(g, 2, 1, "Country", country)
        self.form["fields"] = saved
        v.addLayout(g)
        self.adr_box.addWidget(w)
        self.form["addresses"].append(rec)
        if focus:
            rec["street"].setFocus()
            self.mark_form_dirty()

    def _url_row(self, value, focus=False):
        w = QWidget()
        h = QHBoxLayout(w)
        h.setContentsMargins(0, 0, 0, 0)
        e = QLineEdit(value)
        e.textEdited.connect(self.mark_form_dirty)
        h.addWidget(e, 1)
        rec = {"value": e, "widget": w}

        def remove():
            self.form["urls"].remove(rec)
            w.deleteLater()
            self.mark_form_dirty()

        h.addWidget(self._remove_btn(remove))
        self.url_box.addWidget(w)
        self.form["urls"].append(rec)
        if focus:
            e.setFocus()

    def mark_form_dirty(self, *_):
        if self.form is None:
            return
        self.form_dirty = True
        if not self.dirty:
            self.set_dirty(True)
        self.commit_timer.start()

    def collect_form(self):
        F = self.form
        V = {k: w.text() for k, w in F["fields"].items()}
        V["phones"] = [(r["label"].currentText(), r["value"].text(), r["prop"]) for r in F["phones"]]
        V["emails"] = [(r["label"].currentText(), r["value"].text(), r["prop"]) for r in F["emails"]]
        V["addresses"] = [(r["label"].currentText(), r["street"].text(), r["city"].text(), r["region"].text(),
                           r["postal"].text(), r["country"].text(), r["prop"]) for r in F["addresses"]]
        V["urls"] = [r["value"].text() for r in F["urls"]]
        V["note"] = F["note"].toPlainText()
        return V

    def commit(self):
        self.commit_timer.stop()
        if self.current is None or self.form is None or not self.form_dirty:
            return
        try:
            apply_form(self.current, self.collect_form())
        except RuntimeError:
            return
        self.form_dirty = False
        self.model.refresh(self.current)

    # raw tab -------------------------------------------------------------
    def _build_raw(self, c):
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(0, 14, 0, 16)
        v.setSpacing(10)
        ed = QPlainTextEdit()
        f = QFont("Cascadia Mono" if "Cascadia Mono" in QFontDatabase.families() else "Consolas")
        f.setStyleHint(QFont.Monospace)
        f.setPointSize(10)
        f.setWeight(QFont.Weight.Normal)
        ed.setFont(f)
        ed.setLineWrapMode(QPlainTextEdit.NoWrap)
        raw = serialize_contact(c).replace("\r\n", "\n")
        raw = re.sub(r"^(PHOTO[^:]*:)(.{60}).+?(?=\n[^ ])", r"\1\2…[photo data hidden]", raw, flags=re.M | re.S)
        ed.setPlainText(raw)
        self._hl = VCardHighlighter(ed.document())
        v.addWidget(ed, 1)
        bar = QHBoxLayout()
        bar.addWidget(label("Edit the vCard text directly, then click Apply.", "muted"), 1)
        bar.addWidget(button("Revert", lambda: self.show_editor(c, "raw")))
        bar.addWidget(button("Apply changes", self.apply_raw, "primary"))
        v.addLayout(bar)
        self.raw_edit = ed
        self.raw_photo = c.get("PHOTO")
        return w

    def apply_raw(self):
        c = self.current
        text = self.raw_edit.toPlainText()
        hidden = "…[photo data hidden]" in text
        parsed = parse_vcf(text)
        if len(parsed) != 1:
            info(self, APP_NAME, "The text must contain exactly one BEGIN:VCARD … END:VCARD block.")
            return
        new = parsed[0]
        if hidden and self.raw_photo is not None:
            new.props = [self.raw_photo if p.name == "PHOTO" else p for p in new.props]
        c.props, c.version = new.props, new.version
        self.set_dirty(True)
        self.model.refresh(c)
        self.show_editor(c, "raw")
        self.flash("Raw changes applied")

    # ---------------------------------------------------------------- photos
    def show_photo_preview(self):
        c = self.current
        raw = photo_raw(c.get("PHOTO")) if c is not None else None
        if not raw:
            return
        img = decode_image(raw)
        dlg = Dialog(self, "Photo — " + c.display_name())
        v = QVBoxLayout(dlg)
        v.setContentsMargins(24, 24, 24, 18)
        v.setSpacing(10)
        if img.isNull():
            lb = label("This image can't be shown, but it is kept in the file unchanged.", "muted")
        else:
            dpr = self.devicePixelRatioF()
            box = 480
            pm = QPixmap.fromImage(img)
            if pm.width() > box * dpr or pm.height() > box * dpr:
                pm = pm.scaled(int(box * dpr), int(box * dpr), Qt.KeepAspectRatio, Qt.SmoothTransformation)
            pm.setDevicePixelRatio(dpr)
            lb = QLabel()
            lb.setPixmap(pm)
        lb.setAlignment(Qt.AlignCenter)
        v.addWidget(lb)
        nm = label(c.display_name(), "h2")
        nm.setAlignment(Qt.AlignCenter)
        v.addWidget(nm)
        inf = label(self._photo_info(raw, photo_kind(raw)), "muted")
        inf.setAlignment(Qt.AlignCenter)
        v.addWidget(inf)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(button("Save image…", self.cmd_save_photo, None, "save"))
        close = button("Close", dlg.accept, "primary")
        row.addWidget(close)
        row.addStretch(1)
        v.addLayout(row)
        close.setDefault(True)
        dlg.exec()

    def cmd_save_photo(self):
        c = self.current
        raw = photo_raw(c.get("PHOTO")) if c is not None else None
        if not raw:
            return
        ext = {"JPEG": ".jpg", "PNG": ".png", "GIF": ".gif"}.get(photo_kind(raw), ".bin")
        p, _ = QFileDialog.getSaveFileName(self, "Save photo", (safe_filename(c.display_name()) or "photo") + ext,
                                           "Image (*%s);;All files (*)" % ext)
        if p:
            with open(p, "wb") as fh:
                fh.write(raw)
            self.flash("Photo saved")

    def cmd_set_photo(self):
        c = self.current
        if c is None:
            return
        p, _ = QFileDialog.getOpenFileName(self, "Choose a photo", "", "Images (*.jpg *.jpeg *.png *.gif);;All files (*)")
        if not p:
            return
        with open(p, "rb") as fh:
            raw = fh.read()
        if len(raw) > 512 * 1024 and ask(self, APP_NAME, "This image is %d KB. Large photos make the .vcf big and "
                                         "some phones reject them. Use it anyway?" % (len(raw) // 1024)) != 0:
            return
        self.commit()
        set_photo(c, raw)
        self.set_dirty(True)
        self.model.refresh(c)
        self.show_editor(c)

    def cmd_remove_photo(self):
        c = self.current
        if c is None:
            return
        self.commit()
        c.props = [x for x in c.props if x.name != "PHOTO"]
        self.set_dirty(True)
        self.model.refresh(c)
        self.show_editor(c)

    # ------------------------------------------------------- multi / bulk edit
    def show_multi(self, sel):
        self.commit()
        self.current = None
        self.form = None
        page = QWidget()
        pv = QVBoxLayout(page)
        pv.setContentsMargins(32, 24, 22, 0)
        head = QHBoxLayout()
        self.multi_shown = sel[:4]
        self.multi_avatar = QLabel()
        self.paint_multi_avatars()
        head.addWidget(self.multi_avatar)
        head.addSpacing(14)
        t = QVBoxLayout()
        t.addStretch(1)
        t.addWidget(label("%d contacts selected" % len(sel), "title"))
        t.addWidget(label("Ctrl-click / Shift-click to change the selection, Ctrl+A for all.", "muted"))
        t.addStretch(1)
        head.addLayout(t, 1)
        head.addWidget(button("Export…", self.cmd_export, None, "export"))
        head.addWidget(button("Delete %d" % len(sel), self.delete_selected, "danger", "delete"))
        pv.addLayout(head)
        pv.addSpacing(14)

        body = QWidget()
        bv = QVBoxLayout(body)
        bv.setContentsMargins(0, 0, 10, 30)
        fr, lay = card("Bulk edit — change fields on all %d contacts" % len(sel))
        lay.addWidget(label("Tick a field to change it. Ticked and left empty = clear that field.", "muted"))
        forms = [contact_to_form(c) for c in sel]

        def common(get):
            vals = {get(fm, c) for fm, c in zip(forms, sel)}
            return vals.pop() if len(vals) == 1 else ""

        current = {
            "company": common(lambda fm, c: fm["company"]),
            "department": common(lambda fm, c: fm["department"]),
            "title": common(lambda fm, c: fm["title"]),
            "categories": common(lambda fm, c: c.text("CATEGORIES")),
            "city": common(lambda fm, c: fm["addresses"][0][2] if fm["addresses"] else ""),
            "country": common(lambda fm, c: fm["addresses"][0][5] if fm["addresses"] else ""),
            "note": "",
        }
        g = QGridLayout()
        g.setHorizontalSpacing(14)
        g.setVerticalSpacing(10)
        g.setColumnStretch(1, 1)
        self.bulk = {}
        for r, (key, text) in enumerate(BULK_FIELDS):
            chk = QCheckBox(text)
            e = QLineEdit(current[key])
            e.setEnabled(False)
            chk.toggled.connect(lambda on, e=e: (e.setEnabled(on), on and (e.setFocus(), e.selectAll())))
            g.addWidget(chk, r, 0)
            g.addWidget(e, r, 1)
            self.bulk[key] = (chk, e)
        lay.addLayout(g)
        self.bulk_append = QCheckBox("Notes: add to the existing notes instead of replacing them")
        self.bulk_append.setChecked(True)
        lay.addWidget(self.bulk_append)
        self.bulk_middle = QCheckBox("Middle name: keep only the first one (e.g. the father's name)")
        lay.addWidget(self.bulk_middle)
        cc = QHBoxLayout()
        self.bulk_cc_on = QCheckBox("Add country code to 8-digit phone numbers:")
        self.bulk_cc = QLineEdit("+965")
        self.bulk_cc.setFixedWidth(90)
        cc.addWidget(self.bulk_cc_on)
        cc.addWidget(self.bulk_cc)
        cc.addStretch(1)
        lay.addLayout(cc)
        foot = QHBoxLayout()
        foot.addStretch(1)
        foot.addWidget(button("Apply to %d contacts" % len(sel), lambda: self.apply_bulk(sel), "primary", "edit"))
        lay.addLayout(foot)
        bv.addWidget(fr)
        bv.addStretch(1)
        pv.addWidget(scroll_wrap(body), 1)
        av = self.multi_avatar
        self.set_page(page)
        self.multi_avatar = av

    def paint_multi_avatars(self):
        shown = self.multi_shown
        dpr = self.devicePixelRatioF()
        W = 60 + 38 * (len(shown) - 1)
        pm = QPixmap(int(W * dpr), int(60 * dpr))
        pm.fill(Qt.transparent)
        pm.setDevicePixelRatio(dpr)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        for i, c in enumerate(shown):
            x = i * 38
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(BG))
            p.drawEllipse(QRectF(x, 0, 60, 60))
            p.drawPixmap(x + 2, 2, self.avatar_for(c, 56))
        p.end()
        self.multi_avatar.setPixmap(pm)

    def apply_bulk(self, sel):
        changes = {k: e.text().strip() for k, (chk, e) in self.bulk.items() if chk.isChecked()}
        cc = self.bulk_cc.text().strip() if self.bulk_cc_on.isChecked() else ""
        one_middle = self.bulk_middle.isChecked()
        if not changes and not cc and not one_middle:
            info(self, APP_NAME, "Tick at least one field to change.")
            return
        parts = [t for k, t in BULK_FIELDS if k in changes]
        if one_middle:
            parts.append("middle names (first one only)")
        if cc:
            parts.append("phone country code")
        what = ", ".join(parts)
        if ask(self, APP_NAME, "Change %s on %d contacts?" % (what, len(sel))) != 0:
            return
        bulk_apply(sel, changes, note_append=self.bulk_append.isChecked(), cc=cc, one_middle=one_middle)
        self.set_dirty(True)
        self.refresh_list()
        self.model.refresh()
        self.show_multi(sel)
        self.flash("Updated %d contacts" % len(sel))

    # --------------------------------------------------------------- commands
    def confirm_discard(self):
        self.commit()
        if not self.dirty:
            return True
        r = ask(self, APP_NAME, "Save changes to %s?" % (os.path.basename(self.path) if self.path else "this file"),
                ("Save", "Don't save", "Cancel"))
        if r == 0:
            return self.cmd_save()
        return r == 1

    def open_paths(self, paths, replace):
        paths = [p for p in paths if os.path.isfile(p)]
        for p in paths:
            if p.lower().endswith(SHEET_EXT + ACCESS_EXT):
                ImportDialog(self, p).run()
        vcf = [p for p in paths if not p.lower().endswith(SHEET_EXT + ACCESS_EXT + (".xls",))]
        if not vcf:
            return
        if replace and not self.confirm_discard():
            return
        loaded, errors = [], []
        for p in vcf:
            try:
                loaded += read_vcf_file(p)
            except Exception as ex:
                errors.append("%s: %s" % (os.path.basename(p), ex))
        if errors:
            info(self, APP_NAME, "Could not read:\n" + "\n".join(errors))
        if replace:
            self.current = None
            self.form = None
            self.contacts = loaded
            self.path = vcf[0] if len(vcf) == 1 else None
            self.set_dirty(len(vcf) > 1)
            self.model.set_items([])
            self.flash("Opened %d contacts" % len(loaded))
        else:
            self.commit()
            self.contacts += loaded
            self.set_dirty(True)
            self.flash("Imported %d contacts" % len(loaded))
        self.refresh_list()
        if not self.selected():
            self.show_empty()

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e):
        paths = [u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile()]
        e.acceptProposedAction()
        QTimer.singleShot(0, lambda: self.on_drop(paths))

    def on_drop(self, paths):
        vcf = [p for p in paths if p.lower().endswith(VCF_EXT)]
        other = [p for p in paths if p.lower().endswith(SHEET_EXT + ACCESS_EXT)]
        if vcf:
            if self.contacts:
                r = ask(self, APP_NAME, "Add the contacts from %s to the current list, or open it instead?"
                        % ", ".join(os.path.basename(p) for p in vcf), ("Add to list", "Open instead", "Cancel"))
                if r in (0, 1):
                    self.open_paths(vcf, replace=(r == 1))
            else:
                self.open_paths(vcf, replace=True)
        for p in other:
            ImportDialog(self, p).run()
        if not vcf and not other and paths:
            self.flash("Drop .vcf, Excel, CSV or Access files", DANGER)

    def cmd_open(self):
        p, _ = QFileDialog.getOpenFileNames(self, "Open", "", "vCard files (*.vcf *.vcard);;"
                                            "Excel / CSV / Access (import) (*.xlsx *.xlsm *.csv *.accdb *.mdb);;"
                                            "All files (*)")
        if p:
            self.open_paths(p, replace=True)

    def cmd_import(self):
        p, _ = QFileDialog.getOpenFileNames(
            self, "Import contacts (adds to the current list)", "",
            "Contacts, spreadsheets and databases (*.vcf *.vcard *.xlsx *.xlsm *.csv *.accdb *.mdb);;"
            "vCard files (*.vcf *.vcard);;Excel workbook (*.xlsx *.xlsm);;CSV (*.csv);;"
            "Access database (*.accdb *.mdb);;All files (*)")
        if p:
            self.open_paths(p, replace=False)

    def add_contacts(self, new, source):
        self.commit()
        self.contacts += new
        self.set_dirty(True)
        self.refresh_list()
        if new:
            self.select([new[0]])
        self.flash("Imported %d contacts from %s" % (len(new), source))

    def cmd_export(self):
        self.commit()
        if not self.contacts:
            info(self, APP_NAME, "There are no contacts to export yet.")
            return
        ExportDialog(self).exec()

    def cmd_new_file(self):
        if not self.confirm_discard():
            return
        self.contacts, self.path = [], None
        self.current = None
        self.form = None
        self.set_dirty(False)
        self.refresh_list()
        self.show_empty()

    def cmd_save(self):
        self.commit()
        if not self.path:
            return self.cmd_save_as()
        return self._write(self.path)

    def cmd_save_as(self):
        self.commit()
        init = self.path or "contacts.vcf"
        p, _ = QFileDialog.getSaveFileName(self, "Save vCard file", init, "vCard file (*.vcf)")
        if not p:
            return False
        if not p.lower().endswith(".vcf"):
            p += ".vcf"
        if self._write(p):
            self.path = p
            self.update_title()
            return True
        return False

    def _write(self, p):
        try:
            write_vcf_file(p, self.contacts)
        except Exception as ex:
            info(self, APP_NAME, "Could not save:\n%s" % ex)
            return False
        self.set_dirty(False)
        self.flash("Saved %d contacts" % len(self.contacts))
        self.update_header()
        return True

    def cmd_new_contact(self):
        self.commit()
        c = Contact(version="3.0")
        c.props = [Prop("FN", ""), Prop("N", ["", "", "", "", ""])]
        self.contacts.append(c)
        if self.search.text():
            self.search.clear()
        self.set_dirty(True)
        self.refresh_list()
        self.mode = "details"
        self.select([c])

    def cmd_duplicate(self):
        self.commit()
        sel = self.selected()
        if not sel:
            return
        copies = []
        for c in sel:
            n = Contact([p.copy() for p in c.props if p.name not in ("UID", "REV")], c.version)
            fn = n.get("FN")
            if fn is not None and fn.value:
                fn.value += " (copy)"
            self.contacts.insert(self.contacts.index(c) + 1, n)
            copies.append(n)
        self.set_dirty(True)
        self.refresh_list()
        self.select(copies)

    def delete_selected(self):
        sel = self.selected()
        if not sel:
            return
        what = '"%s"' % sel[0].display_name() if len(sel) == 1 else "%d contacts" % len(sel)
        if ask(self, APP_NAME, "Delete %s?" % what, ("Delete", "Cancel"), danger=True) != 0:
            return
        self.form_dirty = False
        self.current = None
        self.form = None
        rows = [self.model.items.index(c) for c in sel if c in self.model.items]
        idx = min(rows) if rows else 0
        ids = set(map(id, sel))
        self.contacts = [c for c in self.contacts if id(c) not in ids]
        self.list.selectionModel().clearSelection()
        self.set_dirty(True)
        self.refresh_list()
        if self.model.items:
            self.select([self.model.items[min(idx, len(self.model.items) - 1)]])
        else:
            self.show_empty()
        self.flash("Deleted %s" % what)

    def cmd_select_all(self):
        self.list.setFocus()
        self.list.selectAll()

    def cmd_sort(self):
        self.commit()
        self.contacts.sort(key=lambda c: c.display_name().casefold())
        self.set_dirty(True)
        self.refresh_list()
        self.flash("Sorted A → Z")

    def cmd_dedupe(self):
        self.commit()
        seen, out = set(), []
        for c in self.contacts:
            k = duplicate_key(c)
            if k not in seen:
                seen.add(k)
                out.append(c)
        removed = len(self.contacts) - len(out)
        if not removed:
            info(self, APP_NAME, "No duplicates found.\n\nContacts count as duplicates when the name, phone numbers "
                                 "and emails all match.")
            return
        if ask(self, APP_NAME, "Remove %d duplicate contact%s?" % (removed, "" if removed == 1 else "s")) != 0:
            return
        self.contacts = out
        self.current = None
        self.form = None
        self.list.selectionModel().clearSelection()
        self.set_dirty(True)
        self.refresh_list()
        self.show_empty()
        self.flash("Removed %d duplicates" % removed)

    def cmd_about(self):
        info(self, "About " + APP_NAME,
             "%s %s — %s\n\nA free vCard (.vcf) editor.\nReads vCard 2.1, 3.0 and 4.0, imports Excel, CSV and "
             "Access, and exports to Android, iPhone, Google, Outlook, Excel and Access.\n\n"
             "Fields it doesn't understand are always kept." % (APP_NAME, APP_VERSION, APP_TAGLINE))

    def closeEvent(self, e):
        if self.confirm_discard():
            QApplication.instance().removeEventFilter(self)
            e.accept()
        else:
            e.ignore()


# =============================================================================
#  Import dialog (Excel / CSV / Access)
# =============================================================================

def col_letter(i):
    s, i = "", i + 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s


class ImportDialog(Dialog):
    def __init__(self, win, path):
        super().__init__(win, "Import from %s" % os.path.basename(path))
        self.win, self.path = win, path
        self.ok = False
        self.is_access = path.lower().endswith(ACCESS_EXT)
        try:
            if path.lower().endswith(".csv"):
                rows = csv_rows(path)
                self.sources = [(os.path.basename(path), lambda rows=rows: rows)]
            elif self.is_access:
                self.sources = core.access_tables(path)
            else:
                self.sources = [(n, (lambda r=r: r)) for n, r in xlsx_sheets(path) if r]
        except Exception as ex:
            info(win, APP_NAME, "Could not read %s:\n%s\n\nOld .xls files: open them in Excel and use "
                                "Save As → Excel Workbook (.xlsx)." % (os.path.basename(path), ex))
            return
        if not self.sources:
            info(win, APP_NAME, "%s has no tables with data." % os.path.basename(path))
            return
        self.ok = True
        self._cache = {}
        self.resize(860, 720)
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(0)
        inner = QVBoxLayout()
        inner.setContentsMargins(24, 20, 24, 12)
        inner.setSpacing(8)
        v.addLayout(inner, 1)
        inner.addWidget(label("Import contacts", "title"))
        inner.addWidget(label(os.path.basename(path) + " — match each column to a contact field.", "muted"))
        opts = QHBoxLayout()
        opts.setSpacing(10)
        self.src = Combo()
        self.src.addItems([n for n, _ in self.sources])
        self.src.setMinimumWidth(220)
        self.src.currentIndexChanged.connect(self.build)
        if len(self.sources) > 1:
            opts.addWidget(label("Table" if self.is_access else "Sheet", "muted"))
            opts.addWidget(self.src)
            opts.addSpacing(10)
        self.has_header = QCheckBox("First row has column names")
        self.has_header.setChecked(True)
        self.has_header.toggled.connect(self.build)
        if not self.is_access:
            opts.addWidget(self.has_header)
        opts.addStretch(1)
        inner.addLayout(opts)

        self.grid_host = QWidget()
        self.grid = QGridLayout(self.grid_host)
        self.grid.setHorizontalSpacing(12)
        self.grid.setVerticalSpacing(8)
        self.grid.setContentsMargins(0, 6, 10, 6)
        inner.addWidget(scroll_wrap(self.grid_host), 1)

        fr, lay = card()
        r1 = QHBoxLayout()
        self.cc_on = QCheckBox("Add country code to 8-digit phone numbers:")
        self.cc_on.setChecked(True)
        self.cc = QLineEdit("+965")
        self.cc.setFixedWidth(90)
        r1.addWidget(self.cc_on)
        r1.addWidget(self.cc)
        r1.addStretch(1)
        lay.addLayout(r1)
        self.one_middle = QCheckBox("Middle name: keep only the first one (e.g. the father's name)")
        lay.addWidget(self.one_middle)
        r2 = QHBoxLayout()
        r2.addWidget(label("Photos folder (for a Photo column with file names)"))
        self.photo_dir = QLineEdit(os.path.dirname(path))
        r2.addWidget(self.photo_dir, 1)
        r2.addWidget(button("Browse…", self.pick_dir))
        lay.addLayout(r2)
        inner.addWidget(fr)

        foot = QWidget()
        foot.setObjectName("DialogFoot")
        foot.setAttribute(Qt.WA_StyledBackground, True)
        fh = QHBoxLayout(foot)
        fh.setContentsMargins(24, 12, 24, 12)
        self.count = label("", "muted")
        fh.addWidget(self.count, 1)
        fh.addWidget(button("Cancel", self.reject, "ghost"))
        imp = button("Import", self.do_import, "primary", "import")
        imp.setDefault(True)
        fh.addWidget(imp)
        v.addWidget(foot)
        self.build()

    def run(self):
        if self.ok:
            self.exec()

    def pick_dir(self):
        d = QFileDialog.getExistingDirectory(self, "Folder with the photos", self.photo_dir.text())
        if d:
            self.photo_dir.setText(d)

    def rows(self):
        i = max(0, self.src.currentIndex())
        if i not in self._cache:
            QApplication.setOverrideCursor(Qt.WaitCursor)
            try:
                self._cache[i] = self.sources[i][1]()
            except Exception as ex:
                QApplication.restoreOverrideCursor()
                info(self, APP_NAME, "Could not read %s:\n%s" % (self.sources[i][0], ex))
                self._cache[i] = []
            else:
                QApplication.restoreOverrideCursor()
        return self._cache[i]

    def split_rows(self):
        rows = self.rows()
        ncols = max((len(r) for r in rows[:500]), default=0)
        if (self.has_header.isChecked() or self.is_access) and rows:
            headers = [str(x) for x in rows[0]] + [""] * (ncols - len(rows[0]))
            data = rows[1:]
        else:
            headers, data = [""] * ncols, rows
        headers = [h or "Column %s" % col_letter(i) for i, h in enumerate(headers)]
        return headers, data, ncols

    def build(self):
        while self.grid.count():
            it = self.grid.takeAt(0)
            if it.widget():
                it.widget().deleteLater()
        headers, data, ncols = self.split_rows()
        for i, t in enumerate(("COLUMN", "EXAMPLE", "IMPORT AS")):
            self.grid.addWidget(label(t, "section"), 0, i)
        self.grid.setColumnStretch(0, 3)
        self.grid.setColumnStretch(1, 4)
        self.grid.setColumnStretch(2, 3)
        self.maps = []
        fm = QFontMetrics(self.font())
        for i in range(ncols):
            sample = next((r[i] for r in data[:80] if i < len(r) and r[i] not in ("", None)), "")
            if isinstance(sample, (bytes, bytearray)):
                sample = "[image]" if core.extract_image(bytes(sample)) else "[binary data]"
            self.grid.addWidget(label(headers[i]), i + 1, 0)
            self.grid.addWidget(label(fm.elidedText(str(sample), Qt.ElideRight, 280), "muted"), i + 1, 1)
            cb = Combo()
            cb.addItems(IMPORT_FIELDS)
            use_guess = self.has_header.isChecked() or self.is_access
            cb.setCurrentText(guess_field(headers[i]) if use_guess else IMPORT_FIELDS[0])
            self.grid.addWidget(cb, i + 1, 2)
            self.maps.append(cb)
        self.grid.setRowStretch(ncols + 1, 1)
        n = sum(1 for r in data if any(str(x).strip() for x in r if not isinstance(x, (bytes, bytearray))))
        self.count.setText("%d rows will become contacts" % n)

    def do_import(self):
        headers, data, ncols = self.split_rows()
        mapping = [cb.currentText() for cb in self.maps]
        if all(m == IMPORT_FIELDS[0] for m in mapping):
            info(self, APP_NAME, "Choose at least one column to import.")
            return
        cc = self.cc.text().strip() if self.cc_on.isChecked() else ""
        folders = [self.photo_dir.text().strip(), os.path.dirname(self.path)]
        QApplication.setOverrideCursor(Qt.WaitCursor)
        try:
            contacts, misses = rows_to_contacts(headers, data, mapping, cc, folders,
                                                one_middle=self.one_middle.isChecked())
        except Exception as ex:
            QApplication.restoreOverrideCursor()
            info(self, APP_NAME, "Import failed:\n%s" % ex)
            return
        QApplication.restoreOverrideCursor()
        self.accept()
        self.win.add_contacts(contacts, os.path.basename(self.path))
        if misses:
            info(self.win, APP_NAME, "%d photo%s could not be found." % (misses, "" if misses == 1 else "s"))


# =============================================================================
#  Export dialog
# =============================================================================

EXPORT_FORMATS = [
    ("android", "Android phone", "vCard 2.1 (.vcf) — the format Android itself uses. Works on Samsung, "
                                 "Huawei and older phones.", "phone"),
    ("iphone", "iPhone / iCloud", "vCard 3.0 (.vcf) — for iPhone, iCloud.com, Google Contacts and Mac.", "phone"),
    ("google", "Google Contacts (CSV)", "Google's own CSV layout — import at contacts.google.com.", "globe"),
    ("outlook", "Outlook / Microsoft 365 (CSV)", "Column names Outlook expects when importing.", "mail"),
    ("excel", "Excel workbook (.xlsx)", "One row per contact. Photos can be saved to a folder next to it.", "table"),
    ("access", "Access database (.accdb)", "A Contacts table, including photos. Needs the Access database "
                                           "engine (installed with Office).", "db"),
    ("vcard4", "vCard 4.0 (.vcf)", "The newest vCard version, for apps that ask for it.", "contact"),
    ("csv", "CSV, plain (.csv)", "Simple UTF-8 table that opens in Excel with Arabic intact.", "table"),
    ("split", "One .vcf file per contact", "Writes a separate vCard 3.0 file for every contact into a folder.",
     "folder"),
]


class ExportDialog(Dialog):
    def __init__(self, win):
        super().__init__(win, "Export contacts")
        self.win = win
        self.resize(760, 640)
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(0)
        inner = QVBoxLayout()
        inner.setContentsMargins(24, 20, 24, 12)
        inner.setSpacing(10)
        v.addLayout(inner, 1)
        inner.addWidget(label("Export contacts", "title"))
        inner.addWidget(label("Choose where the contacts are going.", "muted"))
        self.formats = QListWidget()
        self.formats.setObjectName("Formats")
        self.formats.setIconSize(QSize(20, 20))
        for key, title, desc, ic in EXPORT_FORMATS:
            it = QListWidgetItem()
            it.setData(Qt.UserRole, key)
            w = QWidget()
            w.setAttribute(Qt.WA_TransparentForMouseEvents)
            h = QHBoxLayout(w)
            h.setContentsMargins(14, 9, 14, 9)
            h.setSpacing(12)
            if Theme.icon_font:
                il = QLabel()
                il.setPixmap(icon(ic, ACCENT, 18).pixmap(22, 22))
                h.addWidget(il)
            tv = QVBoxLayout()
            tv.setSpacing(1)
            tv.addWidget(label(title))
            tv.addWidget(label(desc, "muted", wrap=True))
            h.addLayout(tv, 1)
            it.setSizeHint(QSize(100, 58))
            self.formats.addItem(it)
            self.formats.setItemWidget(it, w)
        self.formats.setCurrentRow(0)
        self.formats.currentRowChanged.connect(self.update_opts)
        self.formats.itemDoubleClicked.connect(lambda *_: self.do_export())
        inner.addWidget(self.formats, 1)

        fr, lay = card("Which contacts")
        row = QHBoxLayout()
        self.scope = QButtonGroup(self)
        sel = win.selected()
        shown = win.model.items
        opts = [("all", "All (%d)" % len(win.contacts), True)]
        if len(shown) != len(win.contacts):
            opts.append(("shown", "Search results (%d)" % len(shown), True))
        opts.append(("selected", "Selected (%d)" % len(sel), bool(sel)))
        self.scope_keys = {}
        for i, (k, t, enabled) in enumerate(opts):
            rb = QRadioButton(t)
            rb.setEnabled(enabled)
            self.scope.addButton(rb, i)
            self.scope_keys[i] = k
            row.addWidget(rb)
            row.addSpacing(16)
        default = next((i for i, (k, t, e) in enumerate(opts) if k == "selected" and e and len(sel) > 1), 0)
        self.scope.button(default).setChecked(True)
        row.addStretch(1)
        lay.addLayout(row)
        self.photos = QCheckBox("Include photos")
        self.photos.setChecked(True)
        lay.addWidget(self.photos)
        inner.addWidget(fr)

        foot = QWidget()
        foot.setObjectName("DialogFoot")
        foot.setAttribute(Qt.WA_StyledBackground, True)
        fh = QHBoxLayout(foot)
        fh.setContentsMargins(24, 12, 24, 12)
        fh.addStretch(1)
        fh.addWidget(button("Cancel", self.reject, "ghost"))
        b = button("Export…", self.do_export, "primary", "export")
        b.setDefault(True)
        fh.addWidget(b)
        v.addWidget(foot)
        self.update_opts()

    def fmt(self):
        it = self.formats.currentItem()
        return it.data(Qt.UserRole) if it else "iphone"

    def update_opts(self, *_):
        f = self.fmt()
        self.photos.setVisible(f not in ("google", "outlook"))
        self.photos.setText("Save photos to a folder next to the file" if f in ("excel", "csv")
                            else "Include photos")

    def chosen(self):
        k = self.scope_keys[self.scope.checkedId()]
        if k == "selected":
            return self.win.selected()
        if k == "shown":
            return list(self.win.model.items)
        return list(self.win.contacts)

    def do_export(self):
        f = self.fmt()
        contacts = self.chosen()
        if not contacts:
            return
        if not self.photos.isChecked() and f in ("android", "iphone", "vcard4", "split", "access"):
            contacts = [Contact([p for p in c.props if p.name != "PHOTO"], c.version) for c in contacts]
        base = os.path.splitext(os.path.basename(self.win.path))[0] if self.win.path else "contacts"
        ext = {"android": ".vcf", "iphone": ".vcf", "vcard4": ".vcf", "google": ".csv", "outlook": ".csv",
               "excel": ".xlsx", "csv": ".csv", "access": ".accdb"}.get(f)
        suffix = {"android": "-android", "iphone": "-iphone", "vcard4": "-v4", "google": "-google",
                  "outlook": "-outlook"}.get(f, "")
        start_dir = os.path.dirname(self.win.path) if self.win.path else ""
        if f == "split":
            d = QFileDialog.getExistingDirectory(self, "Choose a folder for the .vcf files", start_dir)
            if not d:
                return
            target = d
        else:
            filt = {".vcf": "vCard (*.vcf)", ".csv": "CSV (*.csv)", ".xlsx": "Excel workbook (*.xlsx)",
                    ".accdb": "Access database (*.accdb)"}[ext]
            target, _ = QFileDialog.getSaveFileName(self, "Export", os.path.join(start_dir, base + suffix + ext), filt)
            if not target:
                return
            if not target.lower().endswith(ext):
                target += ext
        QApplication.setOverrideCursor(Qt.WaitCursor)
        try:
            extra = ""
            if f == "android":
                write_vcf_file(target, contacts, "2.1")
            elif f == "iphone":
                write_vcf_file(target, contacts, "3.0")
            elif f == "vcard4":
                write_vcf_file(target, contacts, "4.0")
            elif f == "google":
                core.write_google_csv(target, contacts)
            elif f == "outlook":
                core.write_outlook_csv(target, contacts)
            elif f in ("excel", "csv"):
                names = None
                if self.photos.isChecked() and any(c.has_photo() for c in contacts):
                    folder = os.path.splitext(target)[0] + " photos"
                    names = core.export_photos(contacts, folder)
                    extra = "\nPhotos saved to: %s" % folder
                (core.write_excel if f == "excel" else core.write_csv_table)(target, contacts, names)
            elif f == "access":
                core.write_access(target, contacts)
            elif f == "split":
                core.write_split(target, contacts, "3.0")
        except Exception as ex:
            QApplication.restoreOverrideCursor()
            info(self, APP_NAME, "Export failed:\n%s" % ex)
            return
        QApplication.restoreOverrideCursor()
        self.accept()
        self.win.flash("Exported %d contacts" % len(contacts))
        info(self.win, APP_NAME, "Exported %d contacts to:\n%s%s" % (len(contacts), target, extra))


# =============================================================================

def main():
    if IS_WIN:
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("VCFDesk.ContactsManager")
        except Exception:
            pass
    if getattr(sys, "frozen", False):  # packaged .exe: make sure Qt finds its plugins
        base = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
        for sub in (("PySide6", "plugins"), ("plugins",)):
            d = os.path.join(base, *sub)
            if os.path.isdir(d):
                QApplication.addLibraryPath(d)
    QApplication.setHighDpiScaleFactorRoundingPolicy(Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationDisplayName(APP_NAME)
    app.setStyle("Fusion")
    Theme.setup(app)
    win = MainWindow([a for a in sys.argv[1:] if os.path.isfile(a)])
    win.show()
    snap = os.environ.get("VCFDESK_SNAPSHOT")  # build check only: save a screenshot and quit
    if snap:
        def _snap():
            if win.contacts:
                win.select([win.contacts[0]])
            QTimer.singleShot(2500, lambda: (win.grab().save(snap), os._exit(0)))
        QTimer.singleShot(1500, _snap)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
