#!/usr/bin/env python3
from __future__ import annotations
import csv, hashlib, json
from pathlib import Path
from fontTools.ttLib import TTFont, TTCollection

ROOT=Path(__file__).resolve().parents[2]
DOWNLOAD=ROOT/'tools/fonts/downloaded'
CSV=ROOT/'app/src/main/assets/hannom_rcv_standard_nom.csv'
REPORT=ROOT/'tools/fonts/coverage_report.json'
FONTS=[
 {'key':'hanNomPrimary','path':DOWNLOAD/'Minh Nguyen Regular.ttf','source':'https://github.com/TKYKmori/Minh-Nguyen','download':'https://raw.githubusercontent.com/TKYKmori/Minh-Nguyen/main/Minh%20Nguyen%20Regular.ttf','externalLicense':'SIL Open Font License (official README)'},
 {'key':'plangothicP1','path':DOWNLOAD/'PlangothicP1-Regular.ttf','source':'https://github.com/Fitzgerald-Porthmouth-Koenigsegg/Plangothic_Project/releases/tag/V2.9.5795','download':'https://github.com/Fitzgerald-Porthmouth-Koenigsegg/Plangothic_Project/releases/download/V2.9.5795/PlangothicP1-Regular.ttf','externalLicense':'SIL Open Font License 1.1'},
 {'key':'plangothicP2','path':DOWNLOAD/'PlangothicP2-Regular.ttf','source':'https://github.com/Fitzgerald-Porthmouth-Koenigsegg/Plangothic_Project/releases/tag/V2.9.5795','download':'https://github.com/Fitzgerald-Porthmouth-Koenigsegg/Plangothic_Project/releases/download/V2.9.5795/PlangothicP2-Regular.ttf','externalLicense':'SIL Open Font License 1.1'},
]

def names(font):
 def vals(nid):
  out=[]
  for n in font['name'].names:
   if n.nameID==nid:
    try:v=n.toUnicode()
    except:v=''
    if v and v not in out:out.append(v)
  return out
 return {'family':vals(1),'subfamily':vals(2),'fullName':vals(4),'version':vals(5),'license':vals(13),'licenseUrl':vals(14)}

def coverage(font):
 base=set()
 uvs={}
 for table in font['cmap'].tables:
  if table.format==14:
   for selector,pairs in (table.uvsDict or {}).items():uvs.setdefault(selector,set()).update(cp for cp,_ in pairs)
  elif table.isUnicode():base.update(table.cmap.keys())
 return base,uvs

def tokens(text):
 cps=[ord(c) for c in text]
 out=[];i=0
 while i<len(cps):
  cp=cps[i]
  if i+1<len(cps) and (0xFE00<=cps[i+1]<=0xFE0F or 0xE0100<=cps[i+1]<=0xE01EF):out.append((cp,cps[i+1]));i+=2
  else:out.append((cp,None));i+=1
 return out

def supported(token,cov):
 cp,vs=token;base,uvs=cov
 return cp in base and (vs is None or cp in uvs.get(vs,set()))

def main():
 rows=list(csv.DictReader(CSV.open(encoding='utf-8',newline='')))
 loaded=[];details=[]
 for spec in FONTS:
  data=spec['path'].read_bytes();sig=data[:4]
  if sig not in (b'\x00\x01\x00\x00',b'OTTO',b'ttcf',b'true'):raise RuntimeError(f"Not a recognized OpenType font: {spec['path']} signature={sig!r}")
  font=TTFont(spec['path'],lazy=False)
  cov=coverage(font);loaded.append((spec,cov))
  supported_rows=[];missing=[];vs_total=0;vs_supported=0
  for row in rows:
   ts=tokens(row['nom_raw']);ok=all(supported(t,cov) for t in ts)
   if ok:supported_rows.append(int(row['source_row']))
   else:missing.append({'sourceRow':int(row['source_row']),'missingCodePoints':['U+%04X'%cp + (('+U+%04X'%vs) if vs else '') for cp,vs in ts if not supported((cp,vs),cov)]})
   sequences=[t for t in ts if t[1] is not None]
   if sequences:
    vs_total+=1
    if all(supported(t,cov) for t in sequences):vs_supported+=1
  n=names(font)
  details.append({**{k:str(v) if isinstance(v,Path) else v for k,v in spec.items()},'originalFileName':spec['path'].name,'fileSize':len(data),'sha256':hashlib.sha256(data).hexdigest(),'signature':sig.hex(),'internalNames':n,'cmapCodePointCount':len(cov[0]),'variationSelectorCount':len(cov[1]),'supportedRecordCount':len(supported_rows),'totalRecordCount':len(rows),'variationSelectorRecordCount':vs_total,'supportedVariationSelectorRecordCount':vs_supported,'missingRecordCount':len(missing),'missing':missing})
 combined=[];combined_missing=[];vs_total=0;vs_supported=0
 for row in rows:
  ts=tokens(row['nom_raw']);missing_tokens=[t for t in ts if not any(supported(t,cov) for _,cov in loaded)]
  if not missing_tokens:combined.append(int(row['source_row']))
  else:combined_missing.append({'sourceRow':int(row['source_row']),'missingCodePoints':['U+%04X'%cp + (('+U+%04X'%vs) if vs else '') for cp,vs in missing_tokens]})
  seq=[t for t in ts if t[1] is not None]
  if seq:
   vs_total+=1
   if all(any(supported(t,cov) for _,cov in loaded) for t in seq):vs_supported+=1
 report={'csvRecordCount':len(rows),'fonts':details,'combined':{'supportedRecordCount':len(combined),'totalRecordCount':len(rows),'missingRecordCount':len(combined_missing),'missing':combined_missing,'variationSelectorRecordCount':vs_total,'supportedVariationSelectorRecordCount':vs_supported}}
 REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({'fonts':[{k:f[k] for k in ('key','originalFileName','fileSize','sha256','internalNames','supportedRecordCount','missingRecordCount','variationSelectorRecordCount','supportedVariationSelectorRecordCount')} for f in details],'combined':report['combined']},ensure_ascii=True,indent=2))
if __name__=='__main__':main()