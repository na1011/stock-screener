# IFRS 발행사가 미국 순위에서 빠진다 (2026-10-22 실측)

- 무엇에 물었나: 운영 SELECT(재무 0행 · 공시 장부 행 있는 미국 활성 종목 → CIK 386) · SEC `companyconcept`
  `dei/EntityCommonStockSharesOutstanding` · `ifrs-full/ProfitLoss`(없으면 `ifrs-full/Revenue`) · `us-gaap/NetIncomeLoss`(880콜 · `ifrs_probe.py`) ·
  Alpaca 10-21 일봉(364 기호).
- 대조군: 같은 질의가 `us-gaap`을 가진 15곳에 값을 받았다(재무 0행인데 us-gaap이 있는 것 — 따로 본다).
- 결과: 386 중 `ifrs-full`만 329 · 그중 단위 USD만 130. 이름이 예탁증권이 아니고 표지 주식수가 2025-07 뒤인 USD 발행사 중
  보통주 기저 시총 $5B 이상 28(`ifrs.tsv`로 재현 — AEM 93.7B · WPM 60.0B · MT 48.3B · FNV 46.9B …; HSBC · SNN 등은 ADS가 뒤에 있어 비율 미반영).
- 뿌리: `SecEdgarFacts`가 `us-gaap`·`dei`만 읽는다(`facts.path(dei ? "dei" : "us-gaap")`) — 재무 행이 0이라 주식수가 없어 순위 후보 풀에 못 든다.
  그 공시들은 후보 소비가 「소비됨」으로 닫는다(장부 1,119행 중 1,118).
