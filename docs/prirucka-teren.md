# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.160  
**Zařízení:** Chainway C5

Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.

---

## 1. Běžný zápis

1. Zapnout → zelené GPS.
2. Zkontrolovat UDU / výhybku / čip v kartě. Špatně → kap. 2.
3. **Daleko** (tag v koleji) nebo **Blízko** (u antény). Jinak spoušť nefunguje.
4. U 3částové: **Jazyk** / **Rovně** / **Odbočka**.
5. Spoušť.
6. **„Načetli jste“** → **Pokračovat** / **Opakovat**.

![Hlavní obrazovka](../fotky_pro_prirucku/hlavni_strana.png)

*Daleko/Blízko nahoře · kroky UDU → Načtení → Hotovo · karta = výběr · uprostřed nápověda čipu. Barvy: šedá / modrá / zelená / oranžová (chybí režim) / červená (chyba).*

**Úspěch** — platí jen dialog „Načetli jste“:

![Úspěšné načtení](../fotky_pro_prirucku/uspesne_nacteni.png)

![Neúspěšné načtení](../fotky_pro_prirucku/neuspesne_nacteni.png)

*Červené Načtení = znovu přiložit / Daleko↔Blízko / jiná strana tagu.*

| Problém | Řešení |
|---------|--------|
| Spoušť nic / Načtení oranžové | Daleko nebo Blízko |
| Načtení červené | znovu / Daleko↔Blízko / jiná strana tagu |
| GPS nejde | volné místo / Testovací režim GPS |
| Špatná výhybka | kap. 2 |
| Chyba po zápisu | **Smazat** poslední záznam → znovu |

---

## 2. Špatné UDU / výhybka

Klepnout na UDU nebo výhybku v kartě. Po ruční změně GPS znovu až po **Načíst polohu**.

![Výběr UDU](../fotky_pro_prirucku/vyber_udu.png)

![Výběr výhybky](../fotky_pro_prirucku/vyber_vyhybky.png)

*„Chybí N čipů“ = nedokončená · zašedlé = hotové.*

---

## 3. Hranice TUDU

Na hranici úseků (ne běžná výhybka): **Hranice TUDU** → vyplnit → **Použít** → Daleko/Blízko → spoušť.  
Ukončit dřív: změna UDU/výhybky nebo **Načíst polohu**.

![Hranice TUDU](../fotky_pro_prirucku/hranice_tudu.png)

---

## 4. Kontrola

**Kontrola** = přečíst tag, nic nezapisuje. Daleko/Blízko → spoušť.  
Mimo CSV → „Tag není v CSV“.

![Kontrola](../fotky_pro_prirucku/kontrola.png)
