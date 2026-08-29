FROM python:3.11-slim

WORKDIR /app

ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    MODE=poll

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY scripts/daily_report.py scripts/daily_report.py
COPY poll_loop.py .

CMD ["python", "-u", "poll_loop.py"]
