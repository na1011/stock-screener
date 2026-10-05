-- (가) 대상 종목 중 복원 평면 창 07-20~08-31에 편입 경계를 드나든 것(편입 시작·끝이 그 창 안)
select s.ticker, m.origin, m.effective_from, m.effective_to
from universe_membership m join stock s on s.id=m.stock_id
where m.universe_code='US_TOP1000' and s.market_code='US' and s.ticker in ('OGE','NOV','FE','SRE','VFC','AMKR','ED','CTSH','URI','LII','MDLZ','TXNM','EXC','GRMN','PFG','CNP','AXS','EXLS','DUK','VMC','NXPI','WES','ZWS','VRSK','IQV','KNX','TRU','OMF','ESI','QRVO','AXTA','PYPL','FTV','BE','EVRG','AVTR','KNSA','DOW','CARR','ABT','GEHC','VLTO','BG','CLX','KO','OMC','LNT','F','GD','HUBB','AEP','HUM','HBAN','MAS','SPGI','ETR','ONB','D','VICR','NEE','PNR','OSK','PPG','CDNS','CLH','EIX','JCI','LSTR','HCA','RS','BIIB','INCY','LFUS','CB','RMBS','SO','PPL','DTE','POOL')
  and ((m.effective_from between '2026-07-20' and '2026-09-01') or (m.effective_to between '2026-07-20' and '2026-09-01'))
order by 1,3;
