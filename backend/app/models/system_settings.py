from sqlalchemy import Boolean, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base


class SystemSettings(Base):
    __tablename__ = "system_settings"
    id: Mapped[int] = mapped_column(primary_key=True, default=1)
    timezone: Mapped[str] = mapped_column(String(64), default="")
    ntp_server: Mapped[str] = mapped_column(String(256), default="pool.ntp.org")
    # Visits: the same person seen in another camera group within this many
    # seconds joins the visit (Settings -> Camera groups).
    visit_join_seconds: Mapped[int] = mapped_column(Integer, default=60)
    # Media processing (services/gpu_video.py): once a GPU decodes video, the
    # CPU never takes over; if the GPU fails, motion and AI checks pause (an
    # alert says so) until it works again. Off: the CPU stands in.
    media_gpu_only: Mapped[bool] = mapped_column(Boolean, default=True)
