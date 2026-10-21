import re
import zipfile
from pathlib import Path

apk = Path("official-nuvio.apk")

dex_data = b""

with zipfile.ZipFile(apk, "r") as z:
    for name in z.namelist():
        if re.fullmatch(r"classes\d*\.dex", name):
            dex_data += z.read(name)

text = dex_data.decode("latin1", errors="ignore")

supabase_urls = sorted(set(re.findall(
    r"https://[A-Za-z0-9.-]+\.supabase\.co",
    text
)))

publishable_keys = sorted(set(re.findall(
    r"sb_publishable_[A-Za-z0-9_-]+",
    text
)))

jwt_keys = sorted(set(re.findall(
    r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
    text
)))

print("\nSUPABASE URLs")
for value in supabase_urls:
    print(value)

print("\nPUBLISHABLE KEYS")
for value in publishable_keys:
    print(value)

print("\nJWT / ANON KEYS")
for value in jwt_keys:
    print(value)