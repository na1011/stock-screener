#!/bin/bash
# 승인된 판정 16행(사용자 결정 2026-10-05 · 첫 묶음 8 + 둘째 묶음 8). 미해석 행 id는 수집 뒤 ids 단계로 채운다.
set -uo pipefail
S=/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad
J="$S/spin-prod/steps.sh judge"
A="--ca-status=APPROVED --ca-type=SPIN_OFF"
# 첫 묶음
$J 143 $A --ca-ratio=0.5 --ca-price-ratio=0.9832967515 --ca-note=병합1:2+HONA분사·같은날종가·존속0.5×227.80·자식0.5×220.19·v0.5084934932·ADR-0168
$J 46  $A --ca-ratio=0.5529115508 --ca-price-ratio=2.5117271643 --ca-note=인적분할·재상장일시총·존속575258359000·솔브레인357780·2037984292000·v0.2201·ADR-0168
$J 5   $A --ca-ratio=3.0368125137 --ca-price-ratio=5.6137080052 --ca-note=인적분할+액면분할5·재상장일시총·존속12670439037600·SK스퀘어402340·10751535396000·v0.5410·ADR-0168
$J 82  $A --ca-ratio=0.6532307085 --ca-price-ratio=1.5664566577 --ca-note=인적분할·재상장일시총·존속276036931600·현대그린푸드453340·385903610460·v0.4170·ADR-0168
$J 70  $A --ca-ratio=0.6881792396 --ca-price-ratio=1.1536398407 --ca-note=인적분할·재상장일시총·존속1352401700800·OCI456040·914717667000·v0.5965·ADR-0168
$J 212 --ca-price-ratio=1.0116392784 --ca-note=인적분할·재상장일시총·존속14449228037000·한화인더스트리얼솔루션즈489790·1787289006000·v0.8899·ADR-0168
$J 53  $A --ca-ratio=0.6503913086 --ca-price-ratio=0.7360835452 --ca-note=인적분할·재상장일시총·존속82814511339000·삼성에피스홀딩스0126Z0·10911216986500·v0.8836·ADR-0168
$J 210 --ca-price-ratio=0.8579507517 --ca-note=인적분할·재상장일시총·존속6298142735700·한화머시너리앤서비스홀딩스0220W0보통846000505350·v0.8816·ADR-0168
# 둘째 묶음 — FTV·DELL은 id 고정, 나머지는 수집 뒤 인자로 받는다: judgments.sh <FTI> <LEN> <LBTYB> <SYRE> <DNTH> <CNH>
$J 183 $A --ca-ratio=1 --ca-price-ratio=1.1610426812 --ca-note=분할배열로온분사·Fortive5주당VNT2주·같은날종가VNT28.11·FTV69.82·v0.861295·ADR-0168
$J 195 $A --ca-ratio=1 --ca-price-ratio=2.0882114745 --ca-note=분할배열로온분사·Dell8-K0.440626VMW·같은날종가VMW134.87·DELL54.61·v0.478879·ADR-0168
$J $1 $A --ca-ratio=1 --ca-price-ratio=1.3789864030 --ca-note=TechnipFMC공시·5주당TE1주·분배FMV첫날VWAP15.33달러·FTI8.09·ADR-0168
$J $2 $A --ca-ratio=1 --ca-price-ratio=1.0860459644 --ca-note=NYSEex2025-01-21·2주당MRP1주·자식첫관측MRP.WI2025-02-05_21.94·LEN127.49·v0.920771·ADR-0168
$J $3 $A --ca-ratio=1 --ca-price-ratio=1.7558528428 --ca-note=LBTYB1주당SunriseB주ADS2주·액면CHF0.01=A주의1/10·SNRE45.20÷5=9.04·LBTYB11.96·세주식낙폭일치·ADR-0168
$J $4 $A --ca-ratio=1 --ca-price-ratio=1 --ca-note=양도불가CVR·ex일+1.78%·지수+0.12%·60봉σ43%·z0.04·불연속없음→자식가치0·ADR-0168
$J $5 --ca-status=UNSUPPORTED --ca-note=양도불가CVR·ex일-7.74%·지수+0.66%·σ2.61%·z-3.2·원인을못가름→진짜한계미표시(사용자결정2026-10-05)·같은날1:16병합은원장에없다
$J $6 --ca-status=UNSUPPORTED --ca-note=0.2Iveco(EuronextMilan)·소스봉없음·첫날확정종가와발행사공정가치못찾음(시가11.26유로)→진짜한계미표시(사용자결정2026-10-05)
# 2026-10-05 실행 기록: 위 DNTH·CNH 두 줄은 note의 괄호가 bash 구문 오류라 실행되지 않았다(14행까지 적용).
# 두 행은 note를 작은따옴표로 감싸 괄호 없이 따로 돌렸다:
#   steps.sh judge 344 --ca-status=UNSUPPORTED '--ca-note=양도불가CVR·ex일-7.74%·지수+0.66%·σ2.61%·z-3.2·원인을못가름→진짜한계미표시·사용자결정2026-10-05·같은날1:16병합은원장에없다'
#   steps.sh judge 334 --ca-status=UNSUPPORTED '--ca-note=0.2Iveco·EuronextMilan·소스봉없음·첫날확정종가와발행사공정가치못찾음·시가11.26유로→진짜한계미표시·사용자결정2026-10-05'
