# Hranice TUDU – jak zapsat tag na hranici úseku

**Aplikace:** RFID Go GPS  
**Verze aplikace:** 3.146  
**Zařízení:** Chainway C5

---

## K čemu to slouží

Někdy potřebujete zapsat tag **na hranici dvou úseků tratě** – tam, kde končí jeden úsek a začíná druhý. To není běžná výhybka s čipy 1–4, ale **speciální místo**, které se v aplikaci označuje jako **hranice TUDU**.

Tento režim použijte, když:

- stojíte na hranici úseku a potřebujete tam zapsat tag,
- nechcete ručně přepínat běžný zápis výhybky,
- chcete do tabulky uložit, že jde o hranici (ne o část výhybky).

---

## Jak na to – krok za krokem

### 1. Otevřete formulář

Na hlavní obrazovce v kartě **UDU · výhybka** klepněte na tlačítko **Hranice TUDU**.

### 2. Vyplňte údaje

| Co vyplnit | Co tam napsat |
|------------|---------------|
| **TUDU** | Kód úseku, na jehož hranici stojíte. Můžete ho napsat ručně, nebo klepnout na **Vybrat TUDU z okolí** – aplikace nabídne až 10 nejbližších úseků podle GPS. |
| **Objekt** | Co tam fyzicky je – například číslo koleje nebo výhybky (např. `12` nebo `5A`). |
| **KM_EXT** | Volitelné – kilometrická poloha. Když ji teď neznáte, nechte prázdné a doplňte později. |

Potvrďte tlačítkem **Použít**.

### 3. Zapište tag

Aplikace přepne do režimu hranice. V horním náhledu uvidíte **objekt** místo běžné výhybky.

Dál postupujte stejně jako při normálním zápisu:

1. Zvolte výkon **V koleji** nebo **V ruce**.
2. Přiložte tag ke čtečce.
3. Stiskněte **spouště**.

Aplikace tag zapíše, uloží záznam do tabulky a zamkne ho – stejně jako u běžného čipu.

### 4. Co se stane po zápisu

Po úspěšném uložení:

- režim hranice **skončí**,
- aplikace znovu načte **GPS polohu**,
- v režimu GPS automaticky najde **nejbližší další výhybku**, na které můžete pokračovat v běžném zápisu.

Nemusíte nic přepínat ručně – stačí pokračovat dalším tagem.

---

## Jak poznáte, že jste v režimu hranice

- Na obrazovce je označení **Režim hranice TUDU**.
- V náhledu se místo „výhybka“ zobrazuje **objekt**.
- V tabulce CSV je u tohoto záznamu pozice **5** (to znamená hranici úseku).

---

## Jak režim ukončit dřív

Režim hranice skončí také, když:

- ručně změníte UDU nebo výhybku v náhledu,
- klepnete na **Načíst polohu** (aplikace se vrátí k běžnému výběru podle GPS).

---

## Časté dotazy

| Otázka | Odpověď |
|--------|---------|
| Musím mít GPS? | Pro výběr TUDU z okolí ano. TUDU lze zadat i ručně bez GPS. |
| Liší se zápis tagu? | Ne – přiložíte tag a stisknete spouště stejně jako u běžného čipu. |
| Co když udělám chybu? | V panelu **Pokročilé** můžete smazat poslední záznam z tabulky. |
| Můžu hned pokračovat na výhybce? | Ano – po zápisu hranice aplikace sama najde další výhybku podle polohy. |

---

*Krátký návod pro RFID Go GPS. Kompletní příručka k celé aplikaci je v souboru `RFID_Go_GPS_prirucka.pdf`.*
