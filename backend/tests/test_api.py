import io

from fastapi.testclient import TestClient
from PIL import Image

from app.main import app


def _jpeg() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (640, 480), (120, 130, 140)).save(buf, "JPEG")
    return buf.getvalue()


def test_full_flow():
    with TestClient(app) as client:
        assert client.get("/api/health").json()["database"] is True

        insp = client.post("/api/inspections", json={"site_name": "Tower B", "area": "Basement B1", "inspector": "Asha"}).json()
        iid = insp["id"]

        r = client.post(
            f"/api/inspections/{iid}/items",
            files={"photo": ("p.jpg", _jpeg(), "image/jpeg")},
            data={"note": "Fire extinguisher is missing at Exit 2. The safety officer must replace it today."},
        )
        assert r.status_code == 201, r.text
        item = r.json()
        assert item["severity"] == "High" and item["owner"] == "Safety officer" and item["due"] == "Today"
        assert item["engine"] == "rules" and item["needs_review"] == []
        assert client.get(item["photo_url"]).status_code == 200
        assert client.get(item["thumb_url"]).status_code == 200

        r = client.post(f"/api/inspections/{iid}/items", data={"note": "Paint is peeling on the lobby ceiling."})
        second = r.json()
        assert second["location"] == "Basement B1"  # nothing more specific was said, so the walk-through area is used
        assert second["needs_review"] == ["owner", "due"]
        r = client.patch(f"/api/items/{second['id']}", json={"owner": "Painter", "due": "this week", "severity": "Low"})
        assert r.json()["needs_review"] == [] and r.json()["due_date"] is not None

        detail = client.get(f"/api/inspections/{iid}").json()
        assert detail["item_count"] == 2 and detail["high_count"] == 1

        pdf = client.get(f"/api/inspections/{iid}/report.pdf")
        assert pdf.status_code == 200 and pdf.content[:5] == b"%PDF-" and len(pdf.content) > 3000

        actions = client.get(f"/api/inspections/{iid}/actions").json()
        assert actions["count"] == 2 and "Safety officer" in actions["text"]

        assert client.post(f"/api/inspections/{iid}/items").status_code == 422
        assert client.post(f"/api/inspections/{iid}/items", files={"photo": ("x.jpg", b"not an image", "image/jpeg")}).status_code == 422

        assert client.delete(f"/api/items/{second['id']}").status_code == 204
        assert client.delete(f"/api/inspections/{iid}").status_code == 204
        assert client.get(f"/api/inspections/{iid}").status_code == 404
        assert client.get(item["photo_url"]).status_code == 404


def test_demo_walkthrough():
    with TestClient(app) as client:
        demo = client.post("/api/demo").json()
        assert demo["item_count"] == 4 and demo["high_count"] == 2
        titles = [i["title"] for i in demo["items"]]
        assert titles[1] == "Crack on column C4"
        assert all(i["owner"] and i["due"] for i in demo["items"])
        assert client.get(f"/api/inspections/{demo['id']}/report.pdf").content[:5] == b"%PDF-"
