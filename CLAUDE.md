# job-radar: working with it from a Claude session

job-radar does the mechanical half of a job search: it fetches public ATS boards,
rejects postings on facts (seniority, stated years, a country or platform that
cannot work), remembers what it has seen and records decisions. The judging (fit,
order, what the resume leads with, what a letter says) happens in the Claude
session. There is no UI.

Build with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home`
(JDK 26 breaks Hibernate's bytecode generation). Run as
`$JAVA_HOME/bin/java -jar target/job-radar-1.0.0.jar <command>`.

## Reviewing jobs

The full routine, including how to judge and rank, is the personal skill
`~/.claude/skills/job-openings/SKILL.md`, which triggers on "get me today's
openings" and similar from any directory. In short:

1. There is no scheduled run. If the last fetch (the `Last fetch:` line of the
   newest `inbox/` file) is more than a few hours old, run `run` first (a few
   minutes; boards are fetched in parallel, one request at a time per site, and
   the log ends with the five slowest boards).
2. `openings` writes `inbox/openings-<time>.md`: every posting that became a
   recommended candidate since the last review, dated by when it became one (so a
   rule change that makes an old posting eligible still shows up), plus the
   shortlist not yet applied to.
   Relisted copies of a role already decided (same employer and title on any
   board; short generic titles also need the same country) are withheld, and
   cross-board duplicates fold to the direct board's copy. One Oracle
   requisition shown on several of an employer's candidate sites folds to one
   (`OracleTenants.requisitionKey`). A candidate whose description names
   several frontend technologies is flagged. The file is sorted by `FitScore`
   (skills, title level, years, lane, freshness); openings under
   `job-radar.ranking.min-fit` are counted on the "Withheld" line, not listed,
   and postings on switched-off boards are left out.
3. Judge each against the resume (`resume --list`), rank, reply briefly.
4. Record every decision: `mark <id>,<id>,... shortlist|skip --note="..."`, then
   `openings --done`, which closes the window at the moment the file was written.
   A note is added as a new line (history is kept, a repeat is not added);
   `--replace-note` makes it the only one. The handoff file shows the latest line.
5. Only after the user says they applied: `mark <id> applied`. This appends a row
   to the tracker Google Sheet, so never run it speculatively. Later stages:
   `screening`, `interview`, `offer`, `rejected`, `withdrawn`.
6. `export --since=YYYY-MM-DD` remains for looking back over a date range.
7. `links --ids=<picks>` finds the employer's own page for aggregator postings
   (Arbeitnow, Jobicy, We Work Remotely, HN) across Greenhouse, Lever, Ashby,
   SmartRecruiters, Recruitee, Workable and Personio, adds that employer's board
   when job-radar can fetch it, and checks every link (LIVE / DEAD / UNKNOWN;
   Workday maintenance is UNKNOWN, never dead). An UNKNOWN on a company board
   becomes DEAD when that board has fetched fine for 36 hours without the posting. `notify` runs the same check and
   drops dead links. When nothing is found, search the careers site by hand and
   record it with `mark <id> shortlist --url=<link>`; a link set that way is
   always the one checked and sent. An Eightfold job is live while its site's
   sitemap lists it (its own page can 404 while the job is open), and Eightfold
   links are stored in the `/careers?pid=<id>` form.
8. `notify --ids=<picks in ranked order>` posts the picks to the user's Discord
   channel as cards (webhook in `secrets.yml` as `job-radar.notify.discord-webhook`).
   The last line of each posting's `--note` is the card text, and its first word
   sets the colour: "Apply" green, "Stretch" amber. `--dry-run` prints the JSON.
   A review with no picks sends `notify --quiet-day --note="..."` instead: one
   plain message that the review ran, with the shortlist still waiting.

9. The openings file ends with "Check in the browser": Darwinbox careers pages
   (`browser-watch` in application.yml). Darwinbox serves jobs from an API behind
   Cloudflare's bot check, so job-radar does not fetch them and must not try to.
   Read them in a browser during the review; record an application from one with
   `sheet-add`.

## Preparing an application

- Resume: `resume --summary=<id> --pick=e1.3,e1.1,p2.1,... --out=resumes/<company>.pdf`.
  Refs are positions from `resume --list`. It only reorders and omits bullets from
  `~/.config/job-radar/applicant.yml`; it cannot add one, and nothing else should.
- Cover letters and form answers are drafted in chat, for the user to paste. Nothing
  submits an application. The user submits.

## Standing rules

- **Pay on every card (2026-10-19).** `Pay:` in the openings file and the Discord
  card: a stated range when the posting gives one, otherwise an estimate from the
  `job-radar.pay` table (country, employer tier, category, years, London), always
  labelled "estimate" with +-25%. The ask is ~30% up a wide range (top at least
  1.5x bottom), the middle of a narrow one, never under a visa country's floor,
  never the bottom. Pay's big-tech list is its own: `screening.big-tech-boards`
  is about experience checks and holds Citi and Bosch. Re-check the table by its
  `verify-by`.
- **Never invent or inflate a resume claim.** No years, no "expert", no technology
  the resume does not name. Selection and ordering only.
- **Write like a person.** No em or en dashes as punctuation, and none of the stock
  AI phrases ("I am excited to", "leverage", "passionate about").
- **This repository is public.** Personal data lives in `~/.config/job-radar/`
  (applicant.yml, secrets.yml), which is never copied into the repo, a commit
  message, a test fixture or a comment. Audit the diff before any push.
- **Commit locally; push only when asked.**
- **After touching a screening rule**, re-screen a copy of the database and diff the
  verdicts against a backup before trusting it. A green unit test has more than
  once hidden a rule that fired on nothing in the real data.
  `JOB_RADAR_DB=jdbc:sqlite:<copy> ... screen`
- Visa sponsors: `sponsors --refresh` downloads the UK Skilled Worker register
  (gov.uk CSV) and the Dutch IND recognised-sponsor list into
  `~/.config/job-radar/sponsors/` (refresh monthly). UK and NL blocks then carry a
  "Sponsor:" line; an employer not on its register loses 15 fit points and is
  flagged, never rejected (Standard Life is licensed as Phoenix Group).
  `sponsors --check="Name" --country=GB` looks one up.
- Adding a board: `probe --tokens=a,b,c` tries every supported ATS; `--add` saves hits.
  `boards` shows what each board has yielded (`--idle`: stored 20+, never
  recommended); `boards --disable=SOURCE/token` stops fetching one.
  Check a hit's locations and a few descriptions before trusting it: SmartRecruiters
  answers for unknown companies, and some boards hold only placeholder postings.
- Rejections that are facts, not judgement: seniority and stack in the title, a
  stated minimum above 2 years ("up to N years" is a ceiling; a bare "2-10" on
  the line under the degree counts), a stated service bond, a U.S.-persons, ITAR
  or required security clearance clause (`ExportControlRequirement`; export-licence
  boilerplate is not one), and German being required or the
  posting being written in German. Data and analyst titles pass the title check
  (`data`, `analyst`, `bi`, `sql`... in `title-include`) for the data resume. At the employers in `big-tech-boards`
  (formal background checks) the cap is 1 year, and a stated non-internship or
  full-time experience requirement rejects; elsewhere that wording is ignored.
  A title whose only software word is analyst, analytics or BI needs one data
  tool (SQL, Python, a BI tool...) in a full description (`SoftwareSignal`);
  UK placement and undergraduate titles are student-only. Senior wording in a
  description ("expert-level", "mentoring engineers") lowers the fit score but
  never rejects, since it is judgement rather than a stated fact.
  Full-stack, frontend and mobile titles are no longer rejected (2026-10-08):
  until his first job a stack gap is flagged as a Stretch, never filtered out.
  The other platform exclusions (Salesforce, SAP, .NET, security, support...)
  stay, by his choice.
  Abroad only: a stated refusal to sponsor on a
  relocation role, and a requirement to live in the country already.
  Internships: only in India or remote into India, and not when the text
  limits them to enrolled students (`Internship`); the candidate block shows
  whether one converts to full time and how long it runs. Graduate programmes
  and new-grad titles are rejected on the same wording (current enrolment,
  graduating 2026 or later, "graduated within the last year"), in every lane.
  "Authorised to work in <country>" and "right to work ... required" are
  sponsorship refusals, as are "legally entitled to (live and) work" and
  "right to live and work" (2026-10-11). US-only remote with no place ("Remote (Any State)",
  "Nationwide Remote") is US in `geo.countries`. Big tech also
  includes the banks and large India offices (`big-tech-boards`).
  Not software: a title with no software word (and not a bare "Engineer") whose
  full description names fewer than two software tools (`SoftwareSignal`); a
  bare "Engineer" naming no tool over maintenance work (plumbing, HVAC, guest
  repairs) is rejected too. Data-collection drivers, civil, site, facilities and
  teaching titles are in `title-exclude`. Jobgether hides the employer
  (`Employer.hidesEmployer`): its postings score 20 lower and are flagged.
  Hacker News is exempt. The candidate block also flags stated pay below the
  relocation floor, in the floor's currency only.
- `calibrate` scores every decided posting with today's fit score and prints
  picks, read-and-dropped and unread skips by fit band, source and lane: the
  evidence for moving `ranking.min-fit`. It also compares the hand weights with
  weights learned from the decisions (out-of-sample AUC); `calibrate --learn`
  saves the learned ones to `~/.config/job-radar/fit-model.properties` when they
  win by 0.02 (`--force` saves anyway), and `FitScore` uses them from then on.
  The lane is not learned: it stays in his order (remote, Mumbai, rest of India,
  abroad) as a bonus on top, and there is no data-title feature, so data roles
  rank with software ones. Re-run `--learn` monthly. The same command lists
  title words common among read skips and absent from picks (suggestions only),
  the commonest skip reasons, and, once applications exist, replies by source,
  lane and resume.
- `RoleCategory` reads a title as Software / Backend, Data Engineering, Data
  Science / Analytics, AI / ML, Platform / SRE / DevOps, QA / Test Automation,
  Full-stack / Frontend, Mobile (or Other for non-tech), and names the resume that goes
  with it (data categories: Data resume; the rest: Software resume). The
  openings file shows the best five of each category in full first, whatever
  their score, then everything else by fit; each block and each Discord card
  names the category and resume. The tracker's column J holds the category and
  L1:M10 counts applications by category (`sheet-categories`, which fills only
  empty cells; new rows get it on append).
- In the openings file, same-employer openings whose titles differ only in
  brackets are listed under the best one as "Similar at this employer". Each id
  still needs its own decision.
- `notify` checks the whole waiting shortlist, not only the picks it sends: a
  dead page is dropped from the shortlist and named at the top of the post.
- `probe` does not cover Workday. A Workday board is added to `WORKDAY` in
  `config/BoardTokenSeeder` as `tenant/wdN/site`, with an optional fourth part
  that becomes the site search (`mastercard/wd1/CorporateCareers/india`). Big
  global sites need it, or the page limit stops before the India desks.
  Workday lists at most 2,000 per query, newest first. A site bigger than that
  in one country is read as facet slices: a fourth part starting with `@` names
  facet labels joined by `+` (`accenture/wd103/AccentureCareers/@India+Software
  Engineering`), looked up in the site's facets each run. On a capped list a
  stored posting that slid past the end is kept for 45 days after posting
  instead of being closed, and stored postings are not re-read weekly (a first
  run of a slice reads ~500 pages at ~2 s each; later runs only the ~180 new a
  day). A list with no `locationsText` (Accenture) takes the
  place from the bullets after the requisition id. At most 500 detail reads per
  board per run, 8 in flight at once (the per-site gap still spaces them; one
  at a time, Accenture's two slices took 46 minutes after a 3-day gap). Keep the
  cap above ~180: a slice gains that many a day, so a lower cap never catches up.
- `HttpFetchClient` holds at most 64 requests in flight across all sites, so
  the ~3,500 boards starting together no longer time out on connect.
- Avature sites (`AVATURE`, `name/host/lang/portal[/search]`: Siemens India,
  Deloitte USI) are read from the server-rendered search page, following its
  "Next" link; job pages give JSON-LD or labelled fields, and the list row's
  place is the fallback (Deloitte's JSON-LD has no address). Siemens answers in
  about 4 s a page at 6 jobs a page, so its list alone takes ~9 minutes.
  Cognizant (403), HCLTech (location loaded by script, job API closed by
  robots.txt) and Heizen (hand-built page) are on `browser-watch` instead.
- Three employers are read through their own careers APIs (2026-10-23), one
  fetcher each: `IBM` (token = the Location facet's country, `India`; ibm.com's
  search API, since careers.ibm.com is behind an AWS WAF challenge), `CAPGEMINI`
  (token = the API's `country_code`, `en-in`) and `ATLASSIAN` (one board, the
  whole listing). Each list carries the full job text, so no job page is read.
  IBM and Capgemini put their own level ("IBM level: Professional", "Capgemini
  level: Experienced Professionals") on the description's first line, because
  their India jobs rarely state years. SAP, Uber (Cloudflare challenge), Goldman
  Sachs (robots.txt allows only single roles), Flipkart (TurboHire disallows all
  bots) and Infosys (listed nothing) are on `browser-watch`; TCS is left out by
  his choice.
- Second batch (2026-10-23): `JIBE` reads Jibe careers sites (iCIMS behind
  them), token `name/host/location` (`amd/careers.amd.com/India`,
  `booking/jobs.booking.com/Netherlands`); `/api/jobs` carries the full text.
  `DESHAW` reads D. E. Shaw India from its sitemap's `/careers/<slug>-<id>` pages
  (JSON-LD, empty address, so the place is the offices the text names), slug
  title-screened first and stored jobs reused. Red Hat (`redhat/wd5/jobs`) and
  ASML (`asml/wd3/ASMLEXT1`; asml.com's job pages apply through it) are Workday
  seeds; Dell is Oracle on its own host (`dell/enterpriseplatform.dell.com/CX_1/<country id>`).
  Meta (400), LinkedIn (its jobs live on linkedin.com), Zalando (private API)
  and UBS (403) are on `browser-watch`.
- Third batch (2026-10-23): `RIPPLEHIRE` reads RippleHire careers sites, token
  `name/host/careerSiteToken` (LTIMindtree, `ltm/ltimindtree.ripplehire.com/...`).
  The list is JSON when asked for it and states the experience range; the job
  text is read only for a passing title whose stated minimum is 2 years or less
  (others are stored with the range alone). "0 - 0 Years" means not stated.
  eBay, Palo Alto Networks, Samsung (`sec/wd3/Samsung_Careers`) and Cadence are
  Workday searches per country; Arm and Synopsys (Radancy) are `SITEMAP` boards.
  Tech Mahindra (ASP.NET postbacks), Nutanix (Cloudflare) and MathWorks (403)
  are on `browser-watch`.
- Fourth batch (2026-10-23): `SPIRE2GROW` (Myntra; token `name/host`, the
  workspace looked up from the domain and sent as a `workspaceId` header via
  `HttpFetchClient.get(url, fixture, headers)`), `ZOHO_RECRUIT` (Zoho; the
  portal's RSS feed, token `name/host/portal`), and ING as a Radancy `SITEMAP`
  board. Title role words now match their plural ("QA Engineers", "Web
  Developers") when four letters or longer; exclusions stay exact (15 verdicts
  changed, all plurals). Zepto, Postman, Media.net, ABN AMRO, Rabobank and
  Revolut are on `browser-watch`; Zomato/Blinkit (Eternal) post no jobs.
- Fifth batch (2026-10-23): `JsonLdSitemapFetcher` is the base for an
  employer's own site whose sitemap lists job pages with JSON-LD but whose URLs
  `SitemapFetcher` cannot use; each subclass names its sitemap, job-URL pattern
  (url, slug, id) and how it places a job. `DESHAW` and `SOCGEN` (Societe
  Generale, English `/en/job-offers/` pages, JSON-LD city and a real date) use
  it. Nomura is a SuccessFactors `SITEMAP` board (Mumbai in its URLs); Shell,
  Lloyds and Unilever are whole Workday sites. BNP Paribas (403) and NatWest
  (Cloudflare) are on `browser-watch`.
- Analytics firms (2026-10-23), for the data resume: Fractal (Workday),
  Tredence (`RIPPLEHIRE`), ZS Associates (`JIBE`, India and UK) and Tiger
  Analytics (Workable `tiger-analytics`, which is the US entity: Dallas, Toronto,
  Chicago; no India board is published). EXL was already in (Oracle).
  LatentView and Evalueserve are Darwinbox and Mu Sigma lists no openings, so
  all three are on `browser-watch`. Not on big-tech-boards: its 1-year cap is
  for firms with formal experience checks.
- Range titles (2026-10-23): "Analyst/Senior Analyst", "SDET / Senior SDET",
  "Consultant/Senior Consultant" keep their junior end; `withoutRangeUpper`
  drops the seniority word after the slash when the part before it is clean
  and not itself mid-level (Middle/Senior, Experienced/Senior, PL/SR stay
  out). Every other word still counts. Slugs get their slash back
  (`slugWithRanges`) before the JSON-LD sitemap fetchers screen them. 11
  stored verdicts changed; D. E. Shaw gained three Hyderabad candidates.
- India tech centres (2026-10-23): Thomson Reuters, S&P Global and
  Caterpillar are Workday boards on the site's own country facet, one board
  per country (`.../@India`), so a country that empties out fails only its
  own board; GE HealthCare is its whole site (no country facet). Optum, Moody's
  and Synchrony are Radancy `SITEMAP` boards; John Deere is Eightfold
  (`johndeere.eightfold.ai`). `PHENOM` reads Phenom sites one locale's
  sitemap at a time on the `JsonLdSitemapFetcher` base, which now takes the
  board token and skips job pages that answer 404 or 410 (Lowe's sitemap lags
  closed jobs). Home Depot (no Indian office on its Workday), MSCI (iCIMS) and
  Rakuten are on `browser-watch`.
- Indian product companies (2026-10-23): `MAKEMYTRIP` reads
  careers.makemytrip.com's own `/api/jobs` and `/api/jobDetails` (its Darwinbox
  data, minus Darwinbox's bot check), text only for a passing title asking 2
  years or less; `URBANCOMPANY` posts once to Urban Company's `getAllJobs`;
  Sprinklr is a whole Workday site. Delhivery, Rapido (Darwinbox), OYO,
  Chargebee (LinkedIn only), Ather (403), Dream Sports (Lever board gone),
  Zerodha and Nykaa (almost nothing posted) are on `browser-watch`;
  PolicyBazaar and ShareChat publish no list.
- Big employers on their own sites are seeded the same way, in `BoardTokenSeeder`:
  Eightfold (`EIGHTFOLD`, `name/host/domain`: Microsoft, Netflix, Qualcomm; read
  from the site's sitemap, then one detail request per job that passes the slug
  screen), Google (`GOOGLE`, one country per token, early-career and intern
  levels only), Apple (`APPLE`, `locale/location` as in the site's search URL,
  `en-in/india-INDC`; the search page and each job page carry their data as
  embedded JSON, and retail titles drop on the title filter before any job page
  is read), and Oracle's recruiting cloud (`ORACLE_HCM`,
  `name/host/site/locationId`: JPMorgan, Oracle). Any other Eightfold or Oracle
  employer is one seed line. Before every fetch and after discovery,
  `OracleBoardTidier` names each Oracle site after its employer (a site called
  "Candidate Experience site" or "Alumni" takes a sibling site's name, and a
  seeded label wins) and retires test and development pods (`-test`, `-devN`)
  whose live site is also fetched. Microsoft answers 429 to bursts, hence
  `http.site-delays-ms`. At Google a title's "II" is the entry level, so the
  seniority rule ignores it there (`TitleFilter.withoutGoogleEntryLevel`).
- Careers sites with a sitemap of job pages are read by `SITEMAP`
  (`name/host/path-to-sitemap`: Intuit, EY, Standard Chartered, Wipro). It reads
  JSON-LD `JobPosting`, else microdata, else takes the place from the URL, so a
  site qualifies only if its job URLs carry the place. Stored pages are reused
  unless the sitemap's `lastmod` is recent or the stored description is under
  1,000 characters; at most 400 pages are read a run, and a stored page not
  re-read keeps its stored copy. SAP SuccessFactors sites (Wipro) keep the job
  text in blocks beside the `itemprop="description"` element; those are read
  too. A redirect to an illegal URI (Wipro's raw spaces) is followed with the
  characters encoded (`HttpFetchClient.repairedRedirect`).
- `discover` finds boards nobody named: it asks the Internet Archive's URL index
  (Common Crawl's was down) for every Greenhouse, Lever, Ashby, Recruitee,
  SmartRecruiters and Workday board, surveys each new one with one request, and
  adds those with a passing title in India, Ireland, Germany, the Netherlands or
  worldwide remote (bare "Remote" does not count). Every survey is kept in
  `discovered_board`, so a re-run surveys only boards new to the archive;
  `--recheck-days=N` looks again at the skipped ones, `--deep` also pages through
  every archived job URL (slow), `--dry-run` writes nothing. A large Workday site
  is added as one search per country (`tenant/wdN/site/india`).

## Where things are

| | |
|---|---|
| Fetchers, one per ATS or feed (incl. HN, Jobicy, We Work Remotely, Arbeitnow) | `fetch/` |
| Screening rules (title, years, geography, strategy) | `filter/`, `strategy/`, `application.yml` |
| Handoff file | `digest/` |
| Expected pay and the ask (`PayAdvisor`, table at `job-radar.pay`) | `digest/` |
| Decisions and tracker sync | `pipeline/`, `sheets/`, `cli/MarkCommand` |
| Resume rendering | `resume/` |
| Earlier form filling, knowledge resolver and web UI | tag `v1-full` |
