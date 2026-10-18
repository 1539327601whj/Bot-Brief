# -*- coding: utf-8 -*-
"""把配置、DB、限速闸门和各源客户端装配到一处。

worker 与 CLI 共用这一份装配，避免两边各建一个 ``HttpSource``——那样会退化成
「同一个进程里两套限速器」，等于把最小间隔减半。
"""

from __future__ import annotations

from dataclasses import dataclass

from .config import DEFAULT_UA, Config
from .db import WarehouseDb
from .rate_limit import HttpSource, MySqlSourceStateStore, RateGate
from .sources.csindex import CsindexClient
from .sources.danjuan import DanjuanClient
from .sources.eastmoney import EastmoneyClient
from .sources.news import NewsClients
from .sources.sina import SinaClient
from .sources.tencent import TencentClient


@dataclass
class Clients:
    config: Config
    db: WarehouseDb
    gate: RateGate
    http: HttpSource
    eastmoney: EastmoneyClient
    tencent: TencentClient
    sina: SinaClient
    csindex: CsindexClient
    danjuan: DanjuanClient
    news: NewsClients


def build_clients(config: Config) -> Clients:
    db = WarehouseDb(config.db)
    gate = RateGate(config.rate, MySqlSourceStateStore(db))
    http = HttpSource(
        gate, config.rate,
        trust_env=config.trust_env,
        user_agent=DEFAULT_UA,
    )
    return Clients(
        config=config,
        db=db,
        gate=gate,
        http=http,
        eastmoney=EastmoneyClient(http, config),
        tencent=TencentClient(http, config),
        sina=SinaClient(http, config),
        csindex=CsindexClient(http, config),
        danjuan=DanjuanClient(http, config),
        news=NewsClients(http, config),
    )


__all__ = ["Clients", "build_clients"]
