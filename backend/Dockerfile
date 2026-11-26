FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libgl1 \
    libglib2.0-0 \
    curl \
    cifs-utils \
    nfs-common \
    certbot \
    && rm -rf /var/lib/apt/lists/*

# GPU video decoding (services/gpu_video.py): the VAAPI drivers ffmpeg needs
# to decode on the machine's GPU - Intel's media driver (Broadwell and newer,
# from Debian's non-free section) and Mesa's (AMD). They do nothing on a
# machine without a GPU; the NVR then decodes on the CPU.
RUN if [ "$(dpkg --print-architecture)" = "amd64" ]; then \
        sed -i 's/^Components: main$/Components: main non-free non-free-firmware/' /etc/apt/sources.list.d/debian.sources \
        && apt-get update && apt-get install -y --no-install-recommends intel-media-va-driver-non-free mesa-va-drivers \
        && rm -rf /var/lib/apt/lists/*; \
    else \
        apt-get update && apt-get install -y --no-install-recommends mesa-va-drivers && rm -rf /var/lib/apt/lists/*; \
    fi

# Tailscale and cloudflared run as backend-managed subprocesses (started/
# stopped from Settings -> Remote Access, no docker-compose/.env editing
# required) rather than separate sidecar containers, so an auth key or
# tunnel token pasted into the GUI takes effect immediately. Installed from
# each project's own apt repo so the right binary for the host architecture
# (amd64/arm64, e.g. a Raspberry Pi) is picked automatically.
RUN mkdir -p --mode=0755 /usr/share/keyrings \
    && curl -fsSL https://pkgs.tailscale.com/stable/debian/bookworm.noarmor.gpg -o /usr/share/keyrings/tailscale-archive-keyring.gpg \
    && curl -fsSL https://pkgs.tailscale.com/stable/debian/bookworm.tailscale-keyring.list -o /etc/apt/sources.list.d/tailscale.list \
    && curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o /usr/share/keyrings/cloudflare-main.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared bookworm main" > /etc/apt/sources.list.d/cloudflared.list \
    && apt-get update && apt-get install -y --no-install-recommends tailscale cloudflared \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN mkdir -p /data /storage/cache /mnt/primary /mnt/backup /certs/acme-webroot /certs/letsencrypt

EXPOSE 8000

# --timeout-graceful-shutdown: on stop, open live-view (MJPEG) connections never
# finish by themselves; without a cap uvicorn waits on them until Docker kills
# the process, and the shutdown that saves open clips never runs.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--timeout-graceful-shutdown", "5"]
