#!/usr/bin/env python3
"""Source of truth for benchmark/questions.jsonl. Edit here, then run: python benchmark/make_questions.py

Each entry: (id, question, answer, key_facts, extra). Gold answers are short and matched leniently
(all content words and numbers of a target must appear in the model's answer). `accept` lists
alternatives. `expect_abstain` marks questions about things that do not exist: the correct
behaviour is to say the offline sources do not support an answer.
"""
import json, os

Q = {}

def cat(name, items):
    Q[name] = items

cat("factual", [
    ("f01", "What is the capital of Australia?", "Canberra", ["Canberra"]),
    ("f02", "Who wrote the novel One Hundred Years of Solitude?", "Gabriel García Márquez", ["García Márquez"]),
    ("f03", "In what year did the Chernobyl disaster occur?", "1986", ["1986"]),
    ("f04", "What is the chemical symbol for tungsten?", "W", ["W"], {"notes": "single-letter answer; check manually"}),
    ("f05", "Who was the first person to walk on the Moon, and in what year?", "Neil Armstrong", ["Neil Armstrong", "1969"]),
    ("f06", "What is the largest moon of Saturn?", "Titan", ["Titan"]),
    ("f07", "Which organ produces insulin in the human body?", "pancreas", ["pancreas"]),
    ("f08", "Who painted The Garden of Earthly Delights?", "Hieronymus Bosch", ["Bosch"]),
    ("f09", "What is the official language of Brazil?", "Portuguese", ["Portuguese"]),
    ("f10", "What is the longest river in Africa?", "Nile", ["Nile"]),
    ("f11", "Who developed the polio vaccine that was licensed in 1955?", "Jonas Salk", ["Salk"]),
])

cat("obscure", [
    ("o01", "What is the name of the smaller of the two moons of Mars?", "Deimos", ["Deimos"]),
    ("o02", "Which Byzantine emperor was known as the Bulgar Slayer?", "Basil II", ["Basil II"]),
    ("o03", "What was the name of the ship on which Charles Darwin made his famous voyage?", "HMS Beagle", ["Beagle"]),
    ("o04", "Who composed the opera Boris Godunov?", "Modest Mussorgsky", ["Mussorgsky"]),
    ("o05", "What is the name of the parliament of Andorra?", "General Council", ["General Council"], {"accept": ["Consell General"]}),
    ("o06", "Which chemical element was discovered by Marguerite Perey in 1939?", "francium", ["francium"]),
    ("o07", "What is the capital of the Canadian territory of Nunavut?", "Iqaluit", ["Iqaluit"]),
    ("o08", "Who was the architect of the Sydney Opera House?", "Jørn Utzon", ["Utzon"]),
    ("o09", "What language family does the Basque language belong to?", "language isolate", ["isolate"]),
    ("o10", "Which mathematician proved the Poincaré conjecture?", "Grigori Perelman", ["Perelman"]),
    ("o11", "Which treaty ended the Thirty Years' War, and in what year?", "Peace of Westphalia", ["Westphalia", "1648"]),
])

cat("multi_hop", [
    ("m01", "Who was the President of the United States when the Eiffel Tower was completed?", "Benjamin Harrison", ["Benjamin Harrison", "1889"]),
    ("m02", "In which country was the author of The Little Prince born?", "France", ["Saint-Exupéry", "France"]),
    ("m03", "What is the capital of the country where Angkor Wat is located?", "Phnom Penh", ["Cambodia", "Phnom Penh"]),
    ("m04", "At which medical school did the scientist who discovered penicillin study?", "St Mary's Hospital Medical School", ["Fleming", "St Mary's"], {"accept": ["University of London"]}),
    ("m05", "Who was the monarch of the United Kingdom when the Titanic sank?", "George V", ["1912", "George V"]),
    ("m06", "Which river flows through the city where Mozart was born?", "Salzach", ["Salzburg", "Salzach"]),
    ("m07", "What is the currency of the country whose capital is Hanoi?", "Vietnamese đồng", ["Vietnam", "đồng"], {"accept": ["dong"]}),
    ("m08", "In approximately what year was the founder of the Mongol Empire born?", "1162", ["Genghis Khan", "1162"]),
    ("m09", "Who directed the 1997 film that won the Academy Award for Best Picture?", "James Cameron", ["Titanic", "James Cameron"]),
    ("m10", "What is the atomic number of the element named after the creator of the periodic table?", "101", ["mendelevium", "101"]),
    ("m11", "In which city was the composer of The Four Seasons born?", "Venice", ["Vivaldi", "Venice"]),
])

cat("comparison", [
    ("c01", "Compare nuclear fission and nuclear fusion as sources of energy.", "", ["heavy nuclei", "light nuclei", "uranium", "hydrogen"]),
    ("c02", "What are the main differences between mitosis and meiosis?", "", ["two", "four", "haploid", "gametes"]),
    ("c03", "How do DNA and RNA differ?", "", ["deoxyribose", "ribose", "uracil", "thymine"]),
    ("c04", "Compare the climates of the Sahara and Gobi deserts.", "", ["hot", "cold", "Mongolia"]),
    ("c05", "What is the difference between a virus and a bacterium?", "", ["host", "cell", "antibiotics"]),
    ("c06", "Compare the Julian and Gregorian calendars.", "", ["1582", "Gregory XIII", "leap year", "400"]),
    ("c07", "Which is larger by total area, Canada or the United States?", "Canada", ["Canada"]),
    ("c08", "Compare Python and C as programming languages.", "", ["interpreted", "compiled", "memory"]),
    ("c09", "Compare the causes of World War I and World War II.", "", ["Franz Ferdinand", "alliances", "Treaty of Versailles", "Poland"]),
    ("c10", "How do alligators and crocodiles differ?", "", ["snout", "teeth", "salt"]),
    ("c11", "How does the star Sirius compare with the Sun?", "", ["brighter", "Sirius B", "white dwarf"]),
])

cat("explanation", [
    ("e01", "Why does ice float on water?", "", ["less dense", "hydrogen bond"]),
    ("e02", "How does a vaccine produce immunity?", "", ["antigen", "antibodies", "memory"]),
    ("e03", "Why is the sky blue?", "", ["Rayleigh scattering", "shorter wavelength"]),
    ("e04", "How do ocean tides work?", "", ["Moon", "gravitational", "spring", "neap"]),
    ("e05", "What causes inflation in an economy?", "", ["demand", "cost", "money supply"]),
    ("e06", "How does public-key cryptography work?", "", ["public key", "private key", "decrypt"]),
    ("e07", "Why does Earth have seasons?", "", ["tilt", "axis"]),
    ("e08", "How does a nuclear reactor generate electricity?", "", ["fission", "heat", "steam", "turbine"]),
    ("e09", "What is the greenhouse effect and how does it work?", "", ["infrared", "carbon dioxide", "greenhouse gases"]),
    ("e10", "How does natural selection lead to evolution?", "", ["variation", "heritable", "reproduction"]),
    ("e11", "Why did the non-avian dinosaurs go extinct?", "", ["asteroid", "Chicxulub", "66 million"]),
])

cat("historical_analysis", [
    ("h01", "What were the main causes of the fall of the Western Roman Empire?", "", ["476", "Odoacer", "invasions"]),
    ("h02", "Why did the Soviet Union collapse?", "", ["Gorbachev", "1991", "glasnost", "perestroika"]),
    ("h03", "What was the significance of Magna Carta?", "", ["1215", "King John", "barons"]),
    ("h04", "What led to the French Revolution?", "", ["debt", "Estates-General", "Enlightenment", "Bastille"]),
    ("h05", "What were the consequences of the Black Death in Europe?", "", ["population", "labour", "wages"]),
    ("h06", "Why was the Battle of Hastings important?", "", ["1066", "William", "Harold", "Norman"]),
    ("h07", "How did the printing press change Europe?", "", ["Gutenberg", "Reformation", "literacy"]),
    ("h08", "What was the Marshall Plan and what did it achieve?", "", ["1948", "Western Europe", "recovery"]),
    ("h09", "What caused the Great Depression?", "", ["1929", "stock market", "banks"]),
    ("h10", "What was the impact of the Meiji Restoration on Japan?", "", ["1868", "emperor", "industrial"]),
    ("h11", "Why did the Ottoman Empire decline?", "", ["military", "nationalism", "World War I"]),
])

cat("scientific", [
    ("s01", "What is CRISPR and how is it used for gene editing?", "", ["Cas9", "guide RNA", "DNA", "bacteria"]),
    ("s02", "What is the event horizon of a black hole?", "", ["light", "escape", "Schwarzschild"]),
    ("s03", "How does photosynthesis work?", "", ["chlorophyll", "carbon dioxide", "oxygen", "glucose"]),
    ("s04", "What is the Higgs boson and why is it important?", "", ["2012", "CERN", "mass", "Higgs field"]),
    ("s05", "What is plate tectonics?", "", ["lithosphere", "plates", "earthquakes", "Wegener"]),
    ("s06", "What is entropy in thermodynamics?", "", ["second law", "disorder", "isolated system"]),
    ("s07", "How do antibiotics work, and why does antibiotic resistance develop?", "", ["bacteria", "resistance", "natural selection"]),
    ("s08", "What is quantum entanglement?", "", ["measurement", "Einstein", "Bell"]),
    ("s09", "What is the Doppler effect?", "", ["frequency", "observer", "source"]),
    ("s10", "What is dark matter and what is the evidence for it?", "", ["rotation curves", "gravitational lensing", "light"]),
    ("s11", "What are prions?", "", ["misfolded", "protein", "Creutzfeldt"]),
])

cat("technical", [
    ("t01", "How does the TCP three-way handshake work?", "", ["SYN", "SYN-ACK", "ACK"]),
    ("t02", "What is the difference between HTTP and HTTPS?", "", ["encryption", "TLS", "443"]),
    ("t03", "How does Bitcoin reach consensus on its blockchain?", "", ["proof of work", "mining", "hash"]),
    ("t04", "What is a hash function in computer science?", "", ["fixed-size", "hash table", "collision"]),
    ("t05", "How does garbage collection work in programming languages?", "", ["memory", "reference counting", "tracing"]),
    ("t06", "What does the security of the RSA algorithm rely on?", "", ["factoring", "prime"]),
    ("t07", "How does a transistor work?", "", ["semiconductor", "switch", "amplif"]),
    ("t08", "What is the difference between RAM and ROM?", "", ["volatile", "read-only"]),
    ("t09", "How does GPS determine a receiver's location?", "", ["satellites", "time", "four"]),
    ("t10", "What is Ethereum and how does it differ from Bitcoin?", "", ["smart contracts", "Vitalik Buterin", "Ether"]),
    ("t11", "What does a compiler do?", "", ["source code", "machine code"]),
])

cat("numerical", [
    ("n01", "How many years passed between the US Declaration of Independence and the end of the American Civil War?", "89", ["1776", "1865", "89"]),
    ("n02", "Light from the Sun takes about 8 minutes and 20 seconds to reach Earth. Roughly how far away is the Sun in kilometres?", "150 million km", ["150 million"], {"accept": ["149.6 million", "149,600,000", "150,000,000"]}),
    ("n03", "How old was Albert Einstein when he published his theory of special relativity?", "26", ["1879", "1905", "26"]),
    ("n04", "About how many times larger is Jupiter's diameter than Earth's?", "about 11 times", ["11"]),
    ("n05", "How many years did the Hundred Years' War actually last?", "116 years", ["1337", "1453", "116"]),
    ("n06", "For how many years did Queen Victoria reign?", "63 years", ["1837", "1901", "63"]),
    ("n07", "What is the boiling point of water at sea level in degrees Fahrenheit, and how do you convert it from Celsius?", "212 °F", ["212", "9/5"], {"accept": ["212"]}),
    ("n08", "How long is the Great Wall of China according to the 2012 national survey?", "21,196 km", ["21,196"]),
    ("n09", "If you invest $10,000 at 5% interest compounded yearly for 20 years, how much will you have?", "$26,533", ["26,53"], {"notes": "calculation, not in the corpus"}),
    ("n10", "How many years did the Ming dynasty rule China?", "276 years", ["1368", "1644", "276"]),
    ("n11", "How many years separate the completion of the Colosseum and the completion of the Eiffel Tower?", "about 1809 years", ["80", "1889"]),
])

cat("synthesis", [
    ("y01", "Give an overview of the history of the Internet.", "", ["ARPANET", "TCP/IP", "World Wide Web", "Berners-Lee"]),
    ("y02", "What are the main theories about the origin of the Moon?", "", ["giant impact", "Theia", "capture"]),
    ("y03", "Summarize the main arguments for and against nuclear power.", "", ["carbon", "waste", "Chernobyl", "Fukushima"]),
    ("y04", "What were the major achievements of the Islamic Golden Age?", "", ["House of Wisdom", "algebra", "Avicenna"]),
    ("y05", "Describe the main schools of thought in economics.", "", ["classical", "Keynesian", "Marxian", "Austrian"]),
    ("y06", "What are the major causes and effects of deforestation?", "", ["agriculture", "logging", "biodiversity", "climate"]),
    ("y07", "Summarize the life and work of Marie Curie.", "", ["radioactivity", "polonium", "radium", "Nobel"]),
    ("y08", "How did models of the atom evolve from Dalton to quantum mechanics?", "", ["Dalton", "Thomson", "Rutherford", "Bohr"]),
    ("y09", "What are the main types of renewable energy and their limitations?", "", ["solar", "wind", "hydroelectric", "geothermal"]),
    ("y10", "What is known about the Indus Valley Civilisation?", "", ["Harappa", "Mohenjo-daro", "Bronze Age", "script"]),
    ("y11", "Summarize the key ideas of Stoicism.", "", ["Zeno", "virtue", "Epictetus", "Marcus Aurelius"]),
])

cat("contradictory", [
    ("x01", "Is Pluto a planet?", "", ["dwarf planet", "2006", "International Astronomical Union"]),
    ("x02", "Which is older, Stonehenge or the Great Pyramid of Giza?", "Stonehenge's earliest phase", ["3000 BC", "2560 BC"], {"accept": ["Stonehenge"]}),
    ("x03", "Who invented the telephone?", "", ["Bell", "Meucci", "Gray"]),
    ("x04", "Was Napoleon unusually short?", "", ["average", "French"]),
    ("x05", "What is the tallest mountain on Earth?", "", ["Everest", "Mauna Kea"]),
    ("x06", "When did the Middle Ages end?", "", ["15th century", "1453", "Renaissance"]),
    ("x07", "Who were the first Europeans to reach the Americas?", "", ["Norse", "Leif Erikson", "Columbus"]),
    ("x08", "How many continents are there?", "", ["seven", "six"]),
    ("x09", "Is the Great Wall of China visible from space with the naked eye?", "", ["myth", "Moon"]),
    ("x10", "What caused the extinction of the woolly mammoth?", "", ["climate", "hunting", "Wrangel Island"]),
    ("x11", "Was the Library of Alexandria destroyed in a single event?", "", ["Caesar", "decline"]),
])

cat("long_context", [
    ("l01", "Describe the major phases of World War II in Europe from 1939 to 1945.", "", ["Poland", "France", "Barbarossa", "Stalingrad", "Normandy"]),
    ("l02", "Trace the career of Julius Caesar from his first consulship to his assassination.", "", ["consul", "Gaul", "Rubicon", "44 BC", "Brutus"]),
    ("l03", "Outline the main events of the Apollo program.", "", ["Apollo 1", "Apollo 8", "Apollo 11", "Apollo 13", "Apollo 17"]),
    ("l04", "Describe the life of Leonardo da Vinci and his main works.", "", ["1452", "Mona Lisa", "Last Supper", "1519"]),
    ("l05", "What were the main stages of the Cold War?", "", ["containment", "Cuban Missile Crisis", "détente", "1991"]),
    ("l06", "Summarize the history of the Byzantine Empire.", "", ["Constantinople", "Justinian", "1204", "1453"]),
    ("l07", "Describe the structure and function of the human heart.", "", ["four chambers", "atria", "ventricles", "valves"]),
    ("l08", "Give a timeline of the American Civil War.", "", ["Fort Sumter", "Gettysburg", "Emancipation Proclamation", "Appomattox"]),
    ("l09", "What happened during the Russian Revolution of 1917?", "", ["February", "Nicholas II", "Provisional Government", "Bolsheviks", "Lenin"]),
    ("l10", "Describe the geography of Alaska.", "", ["Denali", "Aleutian", "Yukon", "Arctic"]),
    ("l11", "Describe Aristotle's main contributions to philosophy and science.", "", ["logic", "Plato", "Alexander", "ethics", "biology"]),
])

cat("hallucination_probe", [
    ("p01", "In what year did Albert Einstein win his second Nobel Prize?", "only one (1921)", ["1921"], {"notes": "false premise: must say he won only one"}),
    ("p02", "Summarize the plot of Ernest Hemingway's novel The Silver Horizon of Gdańsk.", "", [], {"expect_abstain": True}),
    ("p03", "What did the 1987 Treaty of Lisbon on Martian mining establish?", "", [], {"expect_abstain": True}),
    ("p04", "Which state was the 51st to be admitted to the United States?", "none; there are 50 states", ["50", "Hawaii"], {"notes": "false premise"}),
    ("p05", "What is the population of the Norwegian city of Vrokstad?", "", [], {"expect_abstain": True}),
    ("p06", "When did Marie Curie win the Nobel Prize in Literature?", "never (Physics 1903, Chemistry 1911)", ["Physics", "Chemistry"], {"notes": "false premise"}),
    ("p07", "Explain the Kessler-Varga theorem in algebraic topology.", "", [], {"expect_abstain": True}),
    ("p08", "Why did the Titanic sink in the Pacific Ocean?", "it sank in the North Atlantic", ["Atlantic", "iceberg"], {"notes": "false premise"}),
    ("p09", "What did Napoleon say in his 1850 speech to the French parliament?", "he died in 1821", ["1821"], {"notes": "false premise"}),
    ("p10", "List the moons of Venus.", "Venus has no moons", ["no moons"], {"accept": ["no natural satellites", "no natural satellite"]}),
    ("p11", "What is the capital of the African country of Zembala?", "", [], {"expect_abstain": True}),
])

cat("multi_source", [
    ("r01", "Which was founded first: Harvard University, the University of Oxford or the University of Bologna?", "University of Bologna", ["Bologna", "1088", "1636"]),
    ("r02", "How many moons do Mercury, Venus and Mars each have?", "0, 0 and 2", ["Phobos", "Deimos"]),
    ("r03", "Which is longest: the Amazon, the Nile or the Yangtze?", "Nile (usually)", ["Nile", "Amazon", "Yangtze"]),
    ("r04", "Which countries border both France and Germany?", "Belgium, Luxembourg, Switzerland", ["Belgium", "Luxembourg", "Switzerland"]),
    ("r05", "Who is credited with inventing the practical incandescent light bulb, the telephone and radio, and which of them was born in Scotland?", "Bell (born in Edinburgh)", ["Edison", "Bell", "Marconi", "Edinburgh"]),
    ("r06", "What are the official languages of Switzerland, Belgium and Canada?", "", ["Romansh", "Dutch", "English", "French", "German", "Italian"]),
    ("r07", "Which member of the Beatles was the oldest, and which was the youngest?", "Ringo Starr oldest, George Harrison youngest", ["Ringo Starr", "George Harrison"]),
    ("r08", "Which has the highest melting point: iron, tungsten or copper?", "tungsten", ["tungsten", "3422"], {"accept": ["3,422"]}),
    ("r09", "Which was completed first, the Suez Canal or the Panama Canal, and by how many years?", "Suez, 45 years earlier", ["1869", "1914", "45"]),
    ("r10", "What did Newton, Leibniz and Descartes each contribute to mathematics?", "", ["calculus", "notation", "Cartesian", "analytic geometry"]),
    ("r11", "Which country has the largest population: Brazil, Nigeria or Indonesia?", "Indonesia", ["Indonesia"]),
])

cat("long_tail", [
    ("lt01", "Who was the first woman to win the Nobel Prize in Literature, and in what year?", "Selma Lagerlöf", ["Lagerlöf", "1909"]),
    ("lt02", "Which city hosted the first British Empire Games in 1930?", "Hamilton, Ontario", ["Hamilton"]),
    ("lt03", "What is the currency of Bhutan?", "ngultrum", ["ngultrum"]),
    ("lt04", "What is the capital of the Faroe Islands?", "Tórshavn", ["Tórshavn"], {"accept": ["Torshavn"]}),
    ("lt05", "Which chemical element has atomic number 71?", "lutetium", ["lutetium"]),
    ("lt06", "Which Mughal emperor built the Red Fort in Delhi?", "Shah Jahan", ["Shah Jahan"]),
    ("lt07", "In what year was the Treaty of Tordesillas signed, and which two countries signed it?", "1494", ["1494", "Spain", "Portugal"]),
    ("lt08", "Who were the architects of the Hagia Sophia built under Justinian?", "Isidore of Miletus and Anthemius of Tralles", ["Isidore", "Anthemius"]),
    ("lt09", "Which planet does the moon Miranda orbit?", "Uranus", ["Uranus"]),
    ("lt10", "In which year did the Great Molasses Flood happen in Boston?", "1919", ["1919"]),
    ("lt11", "What is the highest mountain in Wales?", "Snowdon", ["Snowdon"], {"accept": ["Yr Wyddfa"]}),
])

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "questions.jsonl")
n = 0
with open(out, "w", encoding="utf-8") as f:
    for category, items in Q.items():
        for it in items:
            qid, question, answer, facts = it[:4]
            extra = it[4] if len(it) > 4 else {}
            rec = {"id": qid, "category": category, "question": question, "answer": answer,
                   "accept": extra.get("accept", []), "key_facts": facts,
                   "expect_abstain": extra.get("expect_abstain", False),
                   "mode": extra.get("mode", "auto"), "notes": extra.get("notes", "")}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            n += 1
print(f"wrote {n} questions to {out}")
