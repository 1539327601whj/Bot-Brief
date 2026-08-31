FROM python:3.11-slim

WORKDIR /app

ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    MODE=poll \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=10

COPY requirements.txt .
# 腾讯云机器访问 pypi.org 经常超时；按国内镜像依次重试。
RUN pip install --no-cache-dir \
      -i https://mirrors.cloud.tencent.com/pypi/simple \
      --trusted-host mirrors.cloud.tencent.com \
      -r requirements.txt \
    || pip install --no-cache-dir \
      -i https://pypi.tuna.tsinghua.edu.cn/simple \
      --trusted-host pypi.tuna.tsinghua.edu.cn \
      -r requirements.txt \
    || pip install --no-cache-dir \
      -i https://mirrors.aliyun.com/pypi/simple \
      --trusted-host mirrors.aliyun.com \
      -r requirements.txt

COPY scripts/daily_report.py scripts/daily_report.py
COPY scripts/etf_report.py scripts/etf_report.py
COPY scripts/etf_allocation_analyst.py scripts/etf_allocation_analyst.py
COPY poll_loop.py .

CMD ["python", "-u", "poll_loop.py"]
