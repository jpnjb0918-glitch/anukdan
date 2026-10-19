"""Makes the smaller font files in app/src/main/assets/fonts from the Google Fonts originals (OFL).

Keeps the 2,350 common Hangul syllables (KS X 1001), Hangul jamo (for 초성 search hints), Latin, digits and
common symbols, and drops TrueType hinting. Characters left out fall back to the phone's own font.
Fonts with a Reserved Font Name (Nanum Pen Script) are not changed: they are copied as they are.

usage: python3 subset.py <folder with the original .ttf files> <output folder>
originals: https://github.com/google/fonts/tree/main/ofl/<jua|gaegu|gamjaflower|himelody|gowundodum>
needs: pip install fonttools
"""
import os, subprocess, sys

src, out = sys.argv[1], sys.argv[2]
ks = [c for c in range(0xAC00, 0xD7A4) if len(chr(c).encode('euc-kr', errors='ignore')) == 2]
ranges = ['U+0020-007E', 'U+00A0-00FF', 'U+2000-206F', 'U+20A9', 'U+2190-2199', 'U+2460-24FF', 'U+25A0-25FF',
          'U+2600-26FF', 'U+3000-303F', 'U+3131-318E', 'U+FF01-FF5E']
uni = os.path.join(out, 'unicodes.tmp')
open(uni, 'w').write(','.join(ranges + ['U+%04X' % c for c in ks]))
fonts = {'Jua-Regular.ttf': 'jua', 'Gaegu-Regular.ttf': 'gaegu', 'GamjaFlower-Regular.ttf': 'gamjaflower',
         'HiMelody-Regular.ttf': 'himelody', 'GowunDodum-Regular.ttf': 'gowundodum'}
for f, name in fonts.items():
    subprocess.run(['pyftsubset', os.path.join(src, f), f'--unicodes-file={uni}', f'--output-file={os.path.join(out, name + ".ttf")}',
                    '--no-hinting', '--layout-features=*', '--name-IDs=*', '--name-languages=*', '--notdef-outline',
                    '--drop-tables+=DSIG'], check=True)
os.remove(uni)
