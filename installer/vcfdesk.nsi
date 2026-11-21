; VCF Desk installer (NSIS 3, Modern UI 2)
; Installs for all users into C:\Program Files\VCF Desk. Installer and uninstaller run as administrator.
; Build:  makensis installer\vcfdesk.nsi     (expects ..\dist\VCF Desk\ from PyInstaller)

Unicode true
!if /FileExists "${NSISDIR}/Stubs/lzma_solid-amd64-unicode"
  Target amd64-unicode
!endif
SetCompressor /SOLID lzma
SetCompressorDictSize 64

!define APPNAME      "VCF Desk"
!ifndef APPVERSION
  !define APPVERSION "1.0"
!endif
!ifndef VIVERSION
  !define VIVERSION "1.0.0.0"
!endif
!define PUBLISHER    "ilukezippo"
!define EXE          "VCF Desk.exe"
!define PROGID       "VCFDesk.vcf"
!define UNINST_KEY   "Software\Microsoft\Windows\CurrentVersion\Uninstall\VCF Desk"
!ifndef DISTDIR
  !define DISTDIR "..\dist\VCF Desk"
!endif

Name "${APPNAME}"
OutFile "..\dist\VCF-Desk-Setup-${APPVERSION}.exe"
InstallDir "$PROGRAMFILES64\${APPNAME}"
InstallDirRegKey HKLM "${UNINST_KEY}" "InstallLocation"
RequestExecutionLevel admin
BrandingText "${APPNAME} ${APPVERSION}"

VIProductVersion "${VIVERSION}"
VIAddVersionKey "ProductName"     "${APPNAME}"
VIAddVersionKey "FileDescription" "${APPNAME} Setup"
VIAddVersionKey "FileVersion"     "${APPVERSION}"
VIAddVersionKey "ProductVersion"  "${APPVERSION}"
VIAddVersionKey "CompanyName"     "${PUBLISHER}"
VIAddVersionKey "LegalCopyright"  "MIT License"

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"

!define MUI_ICON   "..\assets\vcfdesk.ico"
!define MUI_UNICON "..\assets\vcfdesk.ico"
!define MUI_WELCOMEFINISHPAGE_BITMAP   "wizard.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "wizard.bmp"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_RIGHT
!define MUI_HEADERIMAGE_BITMAP "header.bmp"
!define MUI_ABORTWARNING
!define MUI_COMPONENTSPAGE_SMALLDESC
!define MUI_LANGDLL_ALLLANGUAGES
!define MUI_LANGDLL_REGISTRY_ROOT "HKCU"
!define MUI_LANGDLL_REGISTRY_KEY "Software\VCF Desk"
!define MUI_LANGDLL_REGISTRY_VALUENAME "InstallerLanguage"
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXE}"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "..\LICENSE"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Arabic"

LangString SecCoreName ${LANG_ENGLISH} "VCF Desk (required)"
LangString SecCoreName ${LANG_ARABIC}  "VCF Desk (مطلوب)"
LangString SecCoreDesc ${LANG_ENGLISH} "The app and the Start menu shortcut."
LangString SecCoreDesc ${LANG_ARABIC}  "البرنامج واختصار قائمة ابدأ."
LangString SecDeskName ${LANG_ENGLISH} "Desktop shortcut"
LangString SecDeskName ${LANG_ARABIC}  "اختصار على سطح المكتب"
LangString SecDeskDesc ${LANG_ENGLISH} "Put a VCF Desk icon on the desktop."
LangString SecDeskDesc ${LANG_ARABIC}  "وضع أيقونة VCF Desk على سطح المكتب."
LangString SecAssocName ${LANG_ENGLISH} "Open .vcf files with VCF Desk"
LangString SecAssocName ${LANG_ARABIC}  "فتح ملفات ‎.vcf‎ بواسطة VCF Desk"
LangString SecAssocDesc ${LANG_ENGLISH} "Adds VCF Desk to 'Open with' for contact files (.vcf)."
LangString SecAssocDesc ${LANG_ARABIC}  "إضافة VCF Desk إلى قائمة «فتح باستخدام» لملفات جهات الاتصال (.vcf)."
LangString VcfType ${LANG_ENGLISH} "Contact file"
LangString VcfType ${LANG_ARABIC}  "ملف جهات اتصال"

Function .onInit
  ${If} ${RunningX64}
    SetRegView 64
  ${EndIf}
  !insertmacro MUI_LANGDLL_DISPLAY
FunctionEnd

Function un.onInit
  ${If} ${RunningX64}
    SetRegView 64
  ${EndIf}
  !insertmacro MUI_UNGETLANGUAGE
FunctionEnd

Section "!$(SecCoreName)" SecCore
  SectionIn RO
  ; close a running copy so its files can be replaced
  nsExec::Exec 'taskkill /f /im "${EXE}"'
  SetOutPath "$INSTDIR"
  File /r "${DISTDIR}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  SetShellVarContext all
  CreateShortCut "$SMPROGRAMS\${APPNAME}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\${EXE}" 0
  WriteRegStr   HKLM "${UNINST_KEY}" "DisplayName"     "${APPNAME}"
  WriteRegStr   HKLM "${UNINST_KEY}" "DisplayVersion"  "${APPVERSION}"
  WriteRegStr   HKLM "${UNINST_KEY}" "Publisher"       "${PUBLISHER}"
  WriteRegStr   HKLM "${UNINST_KEY}" "DisplayIcon"     "$INSTDIR\${EXE},0"
  WriteRegStr   HKLM "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr   HKLM "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr   HKLM "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegStr   HKLM "${UNINST_KEY}" "URLInfoAbout"    "https://github.com/ilukezippo/VCF-Desk"
  WriteRegDWORD HKLM "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINST_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKLM "${UNINST_KEY}" "EstimatedSize" "$0"
SectionEnd

Section "$(SecDeskName)" SecDesk
  SetShellVarContext all
  CreateShortCut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\${EXE}" 0
SectionEnd

Section "$(SecAssocName)" SecAssoc
  WriteRegStr HKLM "Software\Classes\${PROGID}" "" "$(VcfType)"
  WriteRegStr HKLM "Software\Classes\${PROGID}\DefaultIcon" "" "$INSTDIR\${EXE},0"
  WriteRegStr HKLM "Software\Classes\${PROGID}\shell\open\command" "" '"$INSTDIR\${EXE}" "%1"'
  WriteRegStr HKLM "Software\Classes\.vcf\OpenWithProgids" "${PROGID}" ""
  WriteRegStr HKLM "Software\Classes\.vcard\OpenWithProgids" "${PROGID}" ""
  WriteRegStr HKLM "Software\Classes\Applications\${EXE}\shell\open\command" "" '"$INSTDIR\${EXE}" "%1"'
  WriteRegStr HKLM "Software\Classes\Applications\${EXE}\SupportedTypes" ".vcf" ""
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecCore}  $(SecCoreDesc)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesk}  $(SecDeskDesc)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecAssoc} $(SecAssocDesc)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  nsExec::Exec 'taskkill /f /im "${EXE}"'
  SetShellVarContext all
  Delete "$SMPROGRAMS\${APPNAME}.lnk"
  Delete "$DESKTOP\${APPNAME}.lnk"
  DeleteRegKey HKLM "Software\Classes\${PROGID}"
  DeleteRegValue HKLM "Software\Classes\.vcf\OpenWithProgids" "${PROGID}"
  DeleteRegValue HKLM "Software\Classes\.vcard\OpenWithProgids" "${PROGID}"
  DeleteRegKey HKLM "Software\Classes\Applications\${EXE}"
  DeleteRegKey HKLM "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\VCF Desk"
  RMDir /r "$INSTDIR"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd
