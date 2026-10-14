#!/usr/bin/env python3
"""Validate standard Android widgets, HONOR metadata and shared theme resources."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "androidApp/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse(ROOT / "androidApp/src/main/AndroidManifest.xml").getroot()
application = manifest.find("application")
providers = [r for r in application.findall("receiver") if r.get(ANDROID + "name").startswith(".widget.") and r.get(ANDROID + "name") != ".widget.CourseWidgetRefreshReceiver"]
assert {r.get(ANDROID + "name") for r in providers} == {
    ".widget.NextCourseWidget", ".widget.TodayCourseWidget", ".widget.WeekCourseWidget",
}
for receiver in providers:
    assert receiver.get(ANDROID + "process") == ":widgetProvider"
    metadata = {m.get(ANDROID + "name"): m for m in receiver.findall("meta-data")}
    info = ET.parse(RES / (metadata["android.appwidget.provider"].get(ANDROID + "resource").replace("@xml/", "xml/") + ".xml")).getroot()
    assert info.get(ANDROID + "previewLayout") and info.get(ANDROID + "previewImage")
    assert info.get(ANDROID + "description")
    assert set(metadata) == {"android.appwidget.provider", "com.hihonor.widget.type"}
    assert metadata["com.hihonor.widget.type"].get(ANDROID + "value") == "honorcard"
    assert info.get(ANDROID + "resizeMode") == "horizontal|vertical"
    assert info.get(ANDROID + "updatePeriodMillis") == "1800000"
    assert receiver.get(ANDROID + "enabled", "true") == "true"
    assert receiver.get(ANDROID + "exported") == "false"
    assert {a.get(ANDROID + "name") for a in receiver.findall("intent-filter/action")} == {"android.appwidget.action.APPWIDGET_UPDATE"}

assert not list(RES.glob("xml/widget_xiaomi_*.xml"))
assert not list(RES.glob("xml/widget_vivo_*.xml"))
assert not any(m.get(ANDROID + "name", "").startswith(("miui", "vivo")) for m in application.iter("meta-data"))

for path in RES.rglob("widget*.xml"):
    tree = ET.parse(path)
    if path.parent.name == "layout" and path.name in {"widget_next_small.xml", "widget_next_wide.xml", "widget_schedule.xml"}:
        assert tree.getroot().get(ANDROID + "id") == "@android:id/background"
        assert tree.getroot().get(ANDROID + "layout_width") == "match_parent"
        assert tree.getroot().get(ANDROID + "layout_height") == "match_parent"

palette = (ROOT / "shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/feature/course/CourseCategoryPalette.kt").read_text()
kinds = {"REQUIRED": "required", "LIMITED": "limited", "ELECTIVE": "elective", "PHYSICAL_EDUCATION": "pe", "UNKNOWN": "unknown", "LAB": "lab", "EXAM": "exam"}
for folder, index in [("values", 0), ("values-night", 2)]:
    colors = {v.get("name"): v.text for v in ET.parse(RES / folder / "widget_colors.xml").getroot()}
    for category, values in re.findall(r"CourseColorCategory\.(\w+) -> CourseCategoryTones\(([^)]+)\)", palette):
        source = [v.strip().replace("0x", "#") for v in values.split(",")]
        assert colors["widget_container_" + kinds[category]] == source[index], category
        assert colors["widget_accent_" + kinds[category]] == source[4], category
print("Validated 3 standard Android providers with HONOR metadata, previews, themes and shared course palette")
