# 아늑단 아이콘 그림 데이터 만들기 (Arcticons → 아늑이 런처)

`app/src/main/assets/linecons/`는 손으로 그린 54종이 없는 앱에 쓰는 아늑단 아이콘의 안쪽 그림이에요. [Arcticons](https://github.com/Arcticons-Team/Arcticons)(Donnnno와 기여자들, **CC BY-SA 4.0**)의 흰 선 SVG 아이콘을 런처가 바로 그릴 수 있는 경로 데이터로 바꾼 거예요. 이 폴더의 데이터는 원작과 같은 CC BY-SA 4.0이에요. 출처와 바꾼 점은 `NOTICE.txt`에 있어요.

| 파일 | 내용 |
|---|---|
| `names.txt` | 아이콘 이름 15,322개 (정렬됨) |
| `icons/NN.txt` | 이름 순서대로 256개씩 나눈 그림 데이터. 한 줄 = 한 아이콘, `|`로 나눈 선/면 목록. 앞의 표시: `W`닫힌 모양 채우기(숫자 = 몇 겹 안쪽인지, 짝수 흰색·홀수 하늘색), `S`굵기, `D`점선 간격, `O`점선 시작, `F`채우기, `E`짝홀 채우기. 뒤는 48×48 상자 기준 SVG 경로(상대 좌표, 소수 둘째 자리) |
| `map/NN.txt` | `패키지/클래스<TAB>아이콘 이름`. `@접두사`는 날짜 아이콘(접두사1~31). NN은 패키지 이름의 Java `hashCode()`를 64로 나눈 나머지 |
| `NOTICE.txt` | 출처·라이선스·바꾼 점 |

## 다시 만들기 (Arcticons 새 버전 반영)

```bash
pip install svgelements shapely cairosvg pillow
git clone --depth 1 https://github.com/Arcticons-Team/Arcticons /tmp/arcticons
python3 tools/linecons/convert.py /tmp/arcticons/icons/white /tmp/arcticons/app/src/main/assets/appfilter.xml app/src/main/assets/linecons
python3 tools/linecons/verify.py /tmp/arcticons/icons/white app/src/main/assets/linecons   # 원본 SVG와 그려서 비교
```

`NOTICE.txt`의 커밋 번호도 같이 바꿔 주세요.

아늑단 틀(`app/src/main/assets/catfmt/`: 뒤에서 내다보는 아늑단 + 타일 4색 + 앞발)은 아늑단 아이콘 팩 그림 코드(`tools/catfmt/pack.py`, `gen.py`)로 만들어요. 이건 Arcticons와 상관없는 우리 그림이에요.

```bash
pip install cairosvg pillow
python3 tools/catfmt/make_frames.py app/src/main/assets/catfmt
# 확인용: 손으로 그린 아이콘 줄 + 자동 아이콘 줄을 한 장으로
python3 tools/catfmt/ref_render.py app/src/main/assets/linecons app/src/main/assets/catfmt sample.png google_chrome,camera,settings
```
