# Recipe evaluation dataset v2

- Corpus: HowToCook `1.6.0`, commit `a0b14bd388ee996943173679c3a7c9db7da04a6c`
- Canonical fingerprint: `f59d67bece51a61cca10f2f70e0424f0ca42f4991003a30d59fbea1fc31de2f6`
- Queries: 90
- Retrieval-positive queries: 78
- Unique Gold recipes: 39
- Multi-Gold queries: 12
- Locked-test retrieval-positive queries: 26
- Locked-test unique Gold recipes: 18
- Maximum impact of one positive test query on a macro average: `100 / 26 = 3.846%`

The v2 split is grouped by connected Gold recipe IDs. A recipe that is relevant to one or more queries appears in only one of dev or test. The locked test fingerprint was recorded only after all source paths and stable IDs passed validation with the production `RecipeCorpusImporter`.

`retrievalExpectation` and `answerExpectation` are independent. `REFUSE_UNSUPPORTED` cases still participate in retrieval metrics because the target recipe must first be found; only the answer is expected to decline unsupported facts.

Grades use 3 for a direct answer and 2 for a valid but less direct alternative. Grade 1 is not used in v2 because binary Recall and MRR currently treat every listed relevant ID equally.

Notable corrections:

- `rq-032` and `rq-056` include all four indexed five-spice/red-braised pork variants that fully satisfy the broad query.
- `rq-033`, `rq-037`, and `rq-049` now say “猪肉” rather than implying all meat categories.
- `rq-050`, `rq-051`, `rq-052`, and `rq-056` no longer mix unsupported hard constraints or answer-stage instructions into retrieval labels.
- `rq-041` no longer says “只用” because the schema has no excluded-tool field.
- `rq-065`–`rq-082` add noisy language, aliases, long queries, explicit time constraints, multi-answer retrieval and new recipe entities.
- `rq-083`–`rq-086` are near-OOD cooking questions verified absent from the fixed corpus.
- `rq-087`–`rq-090` retrieve a real recipe but require refusal of unsupported nutrition, substitution or storage facts.

The files `v1/queries.jsonl` and `v1/answers.jsonl` remain unchanged for historical result reproduction.
