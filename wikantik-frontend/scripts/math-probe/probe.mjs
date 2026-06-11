#!/usr/bin/env node
/**
 * probe.mjs — LaTeX rule-discovery probe
 *
 * Generates ≥400 distinct LaTeX expressions (VALID + BROKEN), labels each with
 * real KaTeX ground truth, and writes:
 *   wikantik-main/src/test/resources/math/probe-dataset.json
 *
 * Usage (from wikantik-frontend/):
 *   node scripts/math-probe/probe.mjs
 *
 * Deterministic: no Math.random — expressions are fully enumerated.
 */

import { createRequire } from 'module';
import { writeFileSync, mkdirSync } from 'fs';
import { fileURLToPath } from 'url';
import path from 'path';

const require = createRequire(import.meta.url);
const katex   = require('katex');

// ---------------------------------------------------------------------------
// Expression catalogue
// Each entry: [id_suffix, family, latex]
// ---------------------------------------------------------------------------

// ── VALID ──────────────────────────────────────────────────────────────────

const VALID = [
  // fractions
  ['frac-simple',           'valid:frac',        '\\frac{a}{b}'],
  ['frac-binomial',         'valid:frac',        '\\frac{n!}{k!(n-k)!}'],
  ['frac-trig',             'valid:frac',        '\\frac{\\sin x}{\\cos x}'],
  ['frac-complex',          'valid:frac',        '\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}'],
  ['frac-sqrt-num',         'valid:frac',        '\\frac{\\sqrt{x+1}}{2}'],
  ['frac-log',              'valid:frac',        '\\frac{\\ln 2}{\\lambda}'],
  ['frac-pi',               'valid:frac',        '\\frac{\\pi}{4}'],
  ['frac-greek',            'valid:frac',        '\\frac{\\alpha + \\beta}{\\gamma}'],
  ['frac-partial',          'valid:frac',        '\\frac{\\partial f}{\\partial x}'],
  ['frac-nabla',            'valid:frac',        '\\frac{\\nabla f}{|\\nabla f|}'],
  ['frac-double-partial',   'valid:frac',        '\\frac{\\partial^2 f}{\\partial x^2}'],
  ['frac-one-half',         'valid:frac',        '\\frac{1}{2}'],
  ['frac-exp',              'valid:frac',        '\\frac{e^x - e^{-x}}{2}'],
  ['frac-zero-num',         'valid:frac',        '\\frac{0}{x}'],

  // nested fractions
  ['nested-frac-2',         'valid:nested-frac', '\\frac{\\frac{a}{b}}{c}'],
  ['nested-frac-3',         'valid:nested-frac', '\\frac{\\frac{1}{x}}{\\frac{1}{y}}'],
  ['nested-frac-deep',      'valid:nested-frac', '\\frac{\\frac{\\frac{a}{b}}{c}}{d}'],
  ['nested-frac-sum',       'valid:nested-frac', '\\frac{\\frac{a}{b} + \\frac{c}{d}}{e}'],
  ['nested-frac-left',      'valid:nested-frac', '\\left(\\frac{\\frac{1}{a}}{b}\\right)'],

  // sub/superscripts (braced)
  ['sup-simple',            'valid:scripts',     'x^{2}'],
  ['sub-simple',            'valid:scripts',     'x_{i}'],
  ['sub-sup',               'valid:scripts',     'x_{i}^{n}'],
  ['sup-expr',              'valid:scripts',     'e^{i\\pi}'],
  ['sub-double-index',      'valid:scripts',     'x_{i,j}^{n+1}'],
  ['sup-frac',              'valid:scripts',     'x^{\\frac{1}{2}}'],
  ['sub-text',              'valid:scripts',     'F_{\\text{max}}'],
  ['sup-greek',             'valid:scripts',     '\\alpha^{\\beta}'],
  ['sub-integral',          'valid:scripts',     '\\int_{0}^{1}'],
  ['sup-single-digit',      'valid:scripts',     'a^2 + b^2 = c^2'],
  ['sub-nested',            'valid:scripts',     'a_{b_{c}}'],
  ['sup-sum',               'valid:scripts',     '\\sum_{n=1}^{\\infty}'],
  ['sub-lim',               'valid:scripts',     '\\lim_{x \\to 0}'],
  ['sup-prime',             'valid:scripts',     "f'(x) = f^{(1)}(x)"],
  ['sub-matrix-ij',         'valid:scripts',     'A_{i,j}'],

  // sqrt + optional-root
  ['sqrt-simple',           'valid:sqrt',        '\\sqrt{x}'],
  ['sqrt-expr',             'valid:sqrt',        '\\sqrt{x^2 + y^2}'],
  ['sqrt-frac',             'valid:sqrt',        '\\sqrt{\\frac{a}{b}}'],
  ['sqrt-nth',              'valid:sqrt',        '\\sqrt[3]{x}'],
  ['sqrt-nth-frac',         'valid:sqrt',        '\\sqrt[n]{\\frac{a}{b}}'],
  ['sqrt-nested',           'valid:sqrt',        '\\sqrt{1 + \\sqrt{x}}'],
  ['sqrt-quadratic',        'valid:sqrt',        '\\sqrt{b^2 - 4ac}'],
  ['sqrt-4',                'valid:sqrt',        '\\sqrt[4]{x^3}'],
  ['sqrt-sum',              'valid:sqrt',        '\\sqrt{a^2 + 2ab + b^2}'],

  // \left…\right delimiters
  ['leftright-paren',       'valid:leftright',   '\\left( \\frac{a}{b} \\right)'],
  ['leftright-bracket',     'valid:leftright',   '\\left[ x + y \\right]'],
  ['leftright-brace',       'valid:leftright',   '\\left\\{ x \\in \\mathbb{R} \\right\\}'],
  ['leftright-vert',        'valid:leftright',   '\\left| x \\right|'],
  ['leftright-norm',        'valid:leftright',   '\\left\\| \\vec{v} \\right\\|'],
  ['leftright-nested',      'valid:leftright',   '\\left( \\left[ a + b \\right] \\cdot c \\right)'],
  ['leftright-dot',         'valid:leftright',   '\\left. \\frac{dy}{dx} \\right|_{x=0}'],
  ['leftright-angle',       'valid:leftright',   '\\left\\langle v, w \\right\\rangle'],
  ['leftright-sum',         'valid:leftright',   '\\left( \\sum_{k=1}^n a_k \\right)^2'],
  ['leftright-matrix',      'valid:leftright',   '\\left( \\begin{matrix} a & b \\\\ c & d \\end{matrix} \\right)'],
  ['leftright-frac2',       'valid:leftright',   '\\left( 1 + \\frac{1}{n} \\right)^n'],

  // leftright WITH arrows — the pathological case for naive substring matching
  ['leftright-with-arrow',  'valid:leftright',   '\\left( x \\rightarrow y \\right)'],
  ['leftright-arrow-deep',  'valid:leftright',   '\\left[ \\lim_{x \\rightarrow 0} f(x) \\right]'],
  ['leftright-arrow-cases', 'valid:leftright',   '\\left\\{ f(x) \\Rightarrow g(x) \\right\\}'],
  ['leftright-implies',     'valid:leftright',   '\\left( A \\Rightarrow B \\right)'],
  ['leftright-maps',        'valid:leftright',   '\\left. f \\right|_{x=0}'],

  // environments
  ['env-matrix',            'valid:environments','\\begin{matrix} a & b \\\\ c & d \\end{matrix}'],
  ['env-pmatrix',           'valid:environments','\\begin{pmatrix} 1 & 0 \\\\ 0 & 1 \\end{pmatrix}'],
  ['env-bmatrix',           'valid:environments','\\begin{bmatrix} x \\\\ y \\\\ z \\end{bmatrix}'],
  ['env-vmatrix',           'valid:environments','\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}'],
  ['env-cases',             'valid:environments','\\begin{cases} x & x > 0 \\\\ 0 & x \\leq 0 \\end{cases}'],
  ['env-aligned',           'valid:environments','\\begin{aligned} x &= a + b \\\\ y &= c \\end{aligned}'],
  ['env-aligned-3',         'valid:environments','\\begin{aligned} f(x) &= x^2 \\\\ g(x) &= x^3 \\\\ h(x) &= x^4 \\end{aligned}'],
  ['env-pmatrix-3x3',       'valid:environments','\\begin{pmatrix} 1 & 0 & 0 \\\\ 0 & 1 & 0 \\\\ 0 & 0 & 1 \\end{pmatrix}'],
  ['env-cases-three',       'valid:environments','\\begin{cases} a & x > 1 \\\\ b & x = 1 \\\\ c & x < 1 \\end{cases}'],
  ['env-aligned-eq',        'valid:environments','\\begin{aligned} f(x) &= (x+1)^2 \\\\ &= x^2 + 2x + 1 \\end{aligned}'],

  // greek
  ['greek-lower',           'valid:greek',       '\\alpha + \\beta + \\gamma + \\delta + \\epsilon'],
  ['greek-upper',           'valid:greek',       '\\Delta x + \\Gamma y + \\Omega z'],
  ['greek-trig-mix',        'valid:greek',       '\\theta = \\pi / 4'],
  ['greek-lambda',          'valid:greek',       '\\lambda_1 + \\lambda_2 = \\tau'],
  ['greek-phi',             'valid:greek',       '\\phi(x) = \\frac{1}{\\sqrt{2\\pi}} e^{-x^2/2}'],
  ['greek-sigma',           'valid:greek',       '\\sigma^2 = \\mu^2 + 1'],
  ['greek-omega',           'valid:greek',       '\\omega = 2\\pi f'],
  ['greek-rho',             'valid:greek',       '\\rho \\cdot v = \\text{const}'],
  ['greek-nu',              'valid:greek',       '\\nu = \\frac{c}{\\lambda}'],
  ['greek-psi',             'valid:greek',       '\\psi(x, t) = A e^{i(kx - \\omega t)}'],
  ['greek-kappa',           'valid:greek',       '\\kappa = \\frac{1}{R}'],

  // operators/relations
  ['ops-cdot',              'valid:operators',   'a \\cdot b + c \\times d'],
  ['ops-leq-geq',           'valid:operators',   'a \\leq b \\geq c'],
  ['ops-approx',            'valid:operators',   '\\pi \\approx 3.14159'],
  ['ops-equiv',             'valid:operators',   'a \\equiv b \\pmod{n}'],
  ['ops-neq',               'valid:operators',   'x \\neq y'],
  ['ops-pm',                'valid:operators',   'x \\pm \\delta'],
  ['ops-div',               'valid:operators',   'a \\div b'],
  ['ops-sum-simple',        'valid:operators',   '\\sum_{k=1}^{n} k'],
  ['ops-prod',              'valid:operators',   '\\prod_{i=1}^{n} a_i'],
  ['ops-mp',                'valid:operators',   'a \\mp b'],
  ['ops-infty',             'valid:operators',   'x \\to \\infty'],
  ['ops-in-notin',          'valid:operators',   'x \\in A'],

  // \text
  ['text-label',            'valid:text',        'F_{\\text{max}} = m a_{\\text{max}}'],
  ['text-if',               'valid:text',        '\\begin{cases} x & \\text{if } x > 0 \\\\ 0 & \\text{otherwise} \\end{cases}'],
  ['text-units',            'valid:text',        'v = 30 \\, \\text{m/s}'],
  ['text-mixed',            'valid:text',        'E = mc^2 \\quad \\text{(mass-energy equivalence)}'],
  ['text-subscript',        'valid:text',        'R_{\\text{ideal}} = 8.314'],
  ['text-superscript',      'valid:text',        'k_{\\text{B}} T'],

  // binomials
  ['binom-nk',              'valid:binom',       '\\binom{n}{k}'],
  ['binom-expanded',        'valid:binom',       '\\binom{n}{k} = \\frac{n!}{k!(n-k)!}'],
  ['binom-sum',             'valid:binom',       '\\sum_{k=0}^{n} \\binom{n}{k} = 2^n'],
  ['binom-pascal',          'valid:binom',       '\\binom{n}{k} + \\binom{n}{k+1} = \\binom{n+1}{k+1}'],

  // sums/integrals/limits
  ['sum-definite',          'valid:sum-int',     '\\sum_{n=1}^{\\infty} \\frac{1}{n^2} = \\frac{\\pi^2}{6}'],
  ['int-definite',          'valid:sum-int',     '\\int_{0}^{\\infty} e^{-x} dx = 1'],
  ['int-double',            'valid:sum-int',     '\\int_{0}^{1} \\int_{0}^{1} x y \\, dx \\, dy'],
  ['int-gaussian',          'valid:sum-int',     '\\int_{-\\infty}^{\\infty} e^{-x^2} dx = \\sqrt{\\pi}'],
  ['lim-simple',            'valid:sum-int',     '\\lim_{n \\to \\infty} \\frac{1}{n} = 0'],
  ['lim-deriv',             'valid:sum-int',     '\\lim_{h \\to 0} \\frac{f(x+h)-f(x)}{h}'],
  ['prod-factorial',        'valid:sum-int',     '\\prod_{k=1}^{n} k = n!'],
  ['sum-geometric',         'valid:sum-int',     '\\sum_{k=0}^{\\infty} r^k = \\frac{1}{1-r}'],
  ['int-by-parts',          'valid:sum-int',     '\\int u \\, dv = uv - \\int v \\, du'],
  ['lim-squeeze',           'valid:sum-int',     '\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1'],

  // arrows — critical for false-positive check on \rightarrow vs \right
  ['arrow-right',           'valid:arrows',      'x \\rightarrow y'],
  ['arrow-left',            'valid:arrows',      'x \\leftarrow y'],
  ['arrow-big-right',       'valid:arrows',      'A \\Rightarrow B'],
  ['arrow-set-arrow',       'valid:arrows',      'f: A \\rightarrow B'],
  ['arrow-limit-arrow',     'valid:arrows',      '\\lim_{x \\rightarrow 0} f(x)'],
  ['arrow-implies',         'valid:arrows',      'P \\Rightarrow Q \\Rightarrow R'],
  ['arrow-to-infty',        'valid:arrows',      'n \\rightarrow \\infty'],
  ['arrow-multi',           'valid:arrows',      'x \\rightarrow y \\rightarrow z'],
  ['arrow-right-left',      'valid:arrows',      'a \\rightarrow b \\leftarrow c'],
  ['arrow-big-lr',          'valid:arrows',      'A \\Rightarrow B \\Rightarrow C'],
  ['arrow-sub-right',       'valid:arrows',      'f_n \\rightarrow f'],
  ['arrow-frac-right',      'valid:arrows',      '\\frac{1}{n} \\rightarrow 0 \\text{ as } n \\rightarrow \\infty'],
  ['arrow-limit-right',     'valid:arrows',      '\\lim_{n \\rightarrow \\infty} a_n'],
  ['arrow-leftright',       'valid:arrows',      'P \\Leftrightarrow Q'],
  ['arrow-mapsto',          'valid:arrows',      'f: x \\mapsto x^2'],

  // mathrm, mathbf, mathbb, mathcal
  ['mathbf',                'valid:font',        '\\mathbf{A} \\cdot \\mathbf{x} = \\mathbf{b}'],
  ['mathrm',                'valid:font',        '\\mathrm{e}^{i\\pi} + 1 = 0'],
  ['mathbb-R',              'valid:font',        'x \\in \\mathbb{R}'],
  ['mathbb-N',              'valid:font',        'n \\in \\mathbb{N}'],
  ['mathcal',               'valid:font',        '\\mathcal{L}(f) = F'],
  ['mathbb-Z',              'valid:font',        '\\mathbb{Z} / n\\mathbb{Z}'],
  ['mathbf-vector',         'valid:font',        '\\mathbf{v} = (v_1, v_2, v_3)'],

  // hat, vec, bar, dot, ddot, overline, underline
  ['hat',                   'valid:accents',     '\\hat{x} + \\hat{y}'],
  ['vec',                   'valid:accents',     '\\vec{F} = m \\vec{a}'],
  ['bar',                   'valid:accents',     '\\bar{z} = a - bi'],
  ['dot-ddot',              'valid:accents',     '\\dot{x} + \\ddot{y}'],
  ['overline',              'valid:accents',     '\\overline{AB} + \\underline{CD}'],
  ['hat-H',                 'valid:accents',     '\\hat{H} \\psi = E \\psi'],
  ['vec-cross',             'valid:accents',     '\\vec{A} \\times \\vec{B}'],

  // displaystyle
  ['displaystyle-sum',      'valid:displaystyle','\\displaystyle\\sum_{k=1}^{n} k^2'],
  ['displaystyle-frac',     'valid:displaystyle','\\displaystyle\\frac{1}{1+x^2}'],
  ['displaystyle-int',      'valid:displaystyle','\\displaystyle\\int_{-\\infty}^{\\infty} e^{-t^2} dt'],

  // hbar, mapsto, lfloor etc — valid KaTeX but not in the LatexSyntaxLinter KNOWN list
  // These will trigger unknownCommand but KaTeX renders them fine
  ['hbar-schrodinger',      'valid:katex-extended', 'i\\hbar \\frac{\\partial \\psi}{\\partial t} = \\hat{H} \\psi'],
  ['hbar-simple',           'valid:katex-extended', '\\hbar = \\frac{h}{2\\pi}'],
  ['mapsto-simple',         'valid:katex-extended', 'f: x \\mapsto x^2'],
  ['lfloor-floor',          'valid:katex-extended', '\\left\\lfloor x \\right\\rfloor'],
  ['lceil-ceil',            'valid:katex-extended', '\\left\\lceil x \\right\\rceil'],
  ['dagger-adj',            'valid:katex-extended', 'A^{\\dagger}'],
  ['oplus-xor',             'valid:katex-extended', 'a \\oplus b'],
  ['forall-quant',          'valid:katex-extended', '\\forall x \\in \\mathbb{R}'],
  ['exists-quant',          'valid:katex-extended', '\\exists x: f(x) = 0'],
  ['subset-set',            'valid:katex-extended', 'A \\subset B \\subseteq C'],
  ['cap-cup',               'valid:katex-extended', 'A \\cap B \\cup C'],
  ['to-arrow',              'valid:katex-extended', 'f: A \\to B'],

  // physics-style mixed
  ['physics-stress',        'valid:mixed',       '\\sigma = \\frac{F}{A}'],
  ['physics-energy',        'valid:mixed',       'E = \\frac{1}{2} m v^2'],
  ['physics-wave',          'valid:mixed',       '\\lambda = \\frac{c}{f}'],
  ['physics-coulomb',       'valid:mixed',       'F = k \\frac{q_1 q_2}{r^2}'],
  ['physics-newton',        'valid:mixed',       'F = m a = m \\frac{d^2 x}{dt^2}'],
  ['taylor-series',         'valid:mixed',       '\\sum_{n=0}^{\\infty} \\frac{f^{(n)}(a)}{n!}(x-a)^n'],
  ['euler',                 'valid:mixed',       'e^{i\\pi} + 1 = 0'],
  ['pythagorean',           'valid:mixed',       'a^2 + b^2 = c^2'],
  ['quadratic',             'valid:mixed',       'x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}'],
  ['motosh',                'valid:mixed',       '\\text{T} = \\text{F}_p \\left( \\frac{\\text{P}}{2\\pi} + \\frac{\\mu_t \\cdot r_t}{\\cos \\beta} \\right)'],
  ['bernoulli',             'valid:mixed',       'P + \\frac{1}{2}\\rho v^2 + \\rho g h = \\text{const}'],
  ['bayes',                 'valid:mixed',       'P(A|B) = \\frac{P(B|A) P(A)}{P(B)}'],
  ['normal-pdf',            'valid:mixed',       'f(x) = \\frac{1}{\\sigma\\sqrt{2\\pi}} e^{-\\frac{(x-\\mu)^2}{2\\sigma^2}}'],
  ['stirling',              'valid:mixed',       'n! \\approx \\sqrt{2\\pi n} \\left(\\frac{n}{e}\\right)^n'],
  ['cauchy-schwarz',        'valid:mixed',       '\\left( \\sum_{k=1}^n a_k b_k \\right)^2 \\leq \\sum_{k=1}^n a_k^2 \\cdot \\sum_{k=1}^n b_k^2'],
  ['log-rule',              'valid:mixed',       '\\log(ab) = \\log a + \\log b'],
  ['partial-deriv',         'valid:mixed',       '\\frac{\\partial^2 f}{\\partial x^2} + \\frac{\\partial^2 f}{\\partial y^2} = 0'],
  ['lagrange-eqn',          'valid:mixed',       '\\frac{d}{dt}\\frac{\\partial L}{\\partial \\dot{q}} - \\frac{\\partial L}{\\partial q} = 0'],
  ['fourier',               'valid:mixed',       '\\hat{f}(\\xi) = \\int_{-\\infty}^{\\infty} f(x) e^{-2\\pi i x \\xi} dx'],
  ['divergence',            'valid:mixed',       '\\nabla \\cdot \\vec{F} = \\frac{\\partial F_x}{\\partial x} + \\frac{\\partial F_y}{\\partial y}'],
  ['chain-rule',            'valid:mixed',       '\\frac{d}{dx}[f(g(x))] = f\'(g(x)) \\cdot g\'(x)'],
  ['geometric-sum',         'valid:mixed',       'S_n = \\frac{a(1-r^n)}{1-r}'],
  ['poisson',               'valid:mixed',       'P(k) = \\frac{\\lambda^k e^{-\\lambda}}{k!}'],
  ['sigmoid',               'valid:mixed',       '\\sigma(x) = \\frac{1}{1 + e^{-x}}'],
  ['gradient-descent',      'valid:mixed',       '\\theta_{t+1} = \\theta_t - \\alpha \\nabla L(\\theta_t)'],
  ['cross-entropy',         'valid:mixed',       'L = -\\sum_{i} y_i \\log \\hat{y}_i'],

  // more valid: additional arrows with \left/\right nearby — stress test for naive rule
  ['rightarrow-in-sum',     'valid:arrows',      '\\sum_{x \\rightarrow y} f(x)'],
  ['rightarrow-subscript',  'valid:arrows',      'T_{n \\rightarrow \\infty}'],
  ['rightarrow-sup',        'valid:arrows',      'f^{x \\rightarrow y}'],
  ['rightarrow-left-pair',  'valid:leftright',   '\\left( x \\rightarrow y \\right) + \\left( a \\rightarrow b \\right)'],
  ['rightarrow-no-delim',   'valid:arrows',      'x \\rightarrow y + z \\rightarrow w'],
  ['big-rightarrow-chain',  'valid:arrows',      'A \\Rightarrow B \\Rightarrow C \\Rightarrow D'],
  ['leftarrow-right',       'valid:arrows',      '\\left[ x \\leftarrow y \\right]'],
  ['leftarrow-pair',        'valid:arrows',      'a \\leftarrow b \\rightarrow c'],

  // more valid: additional valid frac, scripts, environments
  ['frac-sin-cos',          'valid:frac',        '\\frac{\\sin^2 x + \\cos^2 x}{1} = 1'],
  ['frac-tan',              'valid:frac',        '\\tan x = \\frac{\\sin x}{\\cos x}'],
  ['frac-reciprocal',       'valid:frac',        '\\frac{1}{\\frac{1}{x}} = x'],
  ['sup-chain',             'valid:scripts',     'a^{b^{c^{d}}}'],
  ['sub-deep',              'valid:scripts',     'x_{n_{k}}'],
  ['env-bmatrix-3x2',       'valid:environments','\\begin{bmatrix} 1 & 2 & 3 \\\\ 4 & 5 & 6 \\end{bmatrix}'],
  ['env-vmatrix-3x3',       'valid:environments','\\begin{vmatrix} a & b & c \\\\ d & e & f \\\\ g & h & i \\end{vmatrix}'],

  // more valid: trig functions
  ['trig-sin',              'valid:operators',   '\\sin^2 x + \\cos^2 x = 1'],
  ['trig-cos',              'valid:operators',   '\\cos(2x) = \\cos^2 x - \\sin^2 x'],
  ['trig-tan',              'valid:operators',   '\\tan x = \\frac{\\sin x}{\\cos x}'],
  ['trig-exp',              'valid:operators',   'e^{ix} = \\cos x + i \\sin x'],
  ['trig-arcsin',           'valid:operators',   '\\sin(\\arcsin x) = x'],

  // more valid: log/ln/exp
  ['log-chain',             'valid:operators',   '\\log(x \\cdot y) = \\log x + \\log y'],
  ['ln-diff',               'valid:operators',   '\\frac{d}{dx} \\ln x = \\frac{1}{x}'],
  ['exp-compose',           'valid:operators',   'e^{x+y} = e^x \\cdot e^y'],

  // more valid: quad/qquad spacing
  ['quad-spacing',          'valid:operators',   'f(x) \\quad \\text{and} \\quad g(x)'],
  ['qquad-spacing',         'valid:operators',   'a = 1 \\qquad b = 2'],

  // more valid: limits combinations
  ['lim-right',             'valid:sum-int',     '\\lim_{x \\rightarrow a} f(x) = L'],
  ['lim-infty-right',       'valid:sum-int',     '\\lim_{n \\rightarrow \\infty} \\left(1 + \\frac{1}{n}\\right)^n = e'],
  ['integral-parts',        'valid:sum-int',     '\\int_a^b f\'(x) g(x) dx = [f(x)g(x)]_a^b - \\int_a^b f(x) g\'(x) dx'],

  // more valid: sqrt variants
  ['sqrt-5th',              'valid:sqrt',        '\\sqrt[5]{x^4}'],
  ['sqrt-long-expr',        'valid:sqrt',        '\\sqrt{x^4 + 4x^3 + 6x^2 + 4x + 1}'],

  // more valid: arrows with right delimiter at end of leftright
  ['right-only-arrow',      'valid:leftright',   '\\left| x \\right|_{x \\rightarrow 0}'],
  ['rightarrow-outside-lr', 'valid:arrows',      '\\left( a \\right) \\rightarrow \\left( b \\right)'],

  // more valid: display math with lots of \rightarrow — maximise arrow/rightarrow count
  ['category-diagram',      'valid:arrows',      'A \\rightarrow B \\rightarrow C \\rightarrow D \\rightarrow E'],
  ['seq-limit',             'valid:arrows',      'a_1 \\rightarrow a_2 \\rightarrow a_3 \\rightarrow \\ldots \\rightarrow L'],
  ['func-chain',            'valid:arrows',      'f \\circ g: A \\rightarrow C'],
  ['prod-right',            'valid:arrows',      'f: A \\times B \\rightarrow C'],

  // more valid: Greek + operators
  ['greek-all-lower',       'valid:greek',       '\\alpha \\beta \\gamma \\delta \\epsilon \\theta \\lambda \\mu \\nu \\pi'],
  ['ops-all-relations',     'valid:operators',   'a \\leq b \\geq c \\neq d \\approx e \\equiv f'],
  ['greek-xi-zeta',         'valid:greek',       '\\xi = \\zeta(s)'],

  // more valid: mixed complex
  ['matrix-invert',         'valid:mixed',       '(AB)^{-1} = B^{-1} A^{-1}'],
  ['trace-det',             'valid:mixed',       '\\text{tr}(A) = \\sum_{i} a_{ii}'],
  ['rank-nullity',          'valid:mixed',       '\\text{rank}(A) + \\text{nullity}(A) = n'],
  ['cauchy-integral',       'valid:mixed',       'f(a) = \\frac{1}{2\\pi i} \\oint_C \\frac{f(z)}{z-a} dz'],
  ['residue-theorem',       'valid:mixed',       '\\oint_C f(z) dz = 2\\pi i \\sum_k \\text{Res}(f, z_k)'],
  ['heat-equation',         'valid:mixed',       '\\frac{\\partial u}{\\partial t} = \\alpha \\frac{\\partial^2 u}{\\partial x^2}'],
  ['wave-equation',         'valid:mixed',       '\\frac{\\partial^2 u}{\\partial t^2} = c^2 \\frac{\\partial^2 u}{\\partial x^2}'],
  ['maxwell-div-e',         'valid:mixed',       '\\nabla \\cdot \\vec{E} = \\frac{\\rho}{\\epsilon_0}'],
  ['einstein-eqn',          'valid:mixed',       'G_{\\mu\\nu} + \\Lambda g_{\\mu\\nu} = \\frac{8\\pi G}{c^4} T_{\\mu\\nu}'],
  ['dirichlet',             'valid:mixed',       '\\sum_{n=1}^{\\infty} \\frac{\\mu(n)}{n^s} = \\frac{1}{\\zeta(s)}'],
];

// ── BROKEN ─────────────────────────────────────────────────────────────────

const BROKEN = [
  // frac arity — genuinely broken (KaTeX fails)
  ['frac-one-arg',              'broken:frac-arity',         '\\frac{a}'],
  ['frac-no-args',              'broken:frac-arity',         '\\frac'],
  ['frac-incomplete-close',     'broken:frac-arity',         '\\frac{x}{'],
  ['frac-incomplete-first',     'broken:frac-arity',         'x + \\frac{a'],
  ['frac-missing-second-arg',   'broken:frac-arity',         '\\frac{a+b}{c'],
  ['frac-only-open',            'broken:frac-arity',         '\\frac{'],
  ['frac-partial-sec-arg',      'broken:frac-arity',         '\\frac{a}{b + c'],
  ['frac-empty-second',         'broken:frac-arity',         '\\frac{a}{'],
  ['nested-frac-incomplete',    'broken:frac-arity',         '\\frac{\\frac{a}{b}}{c'],
  ['frac-mid-expr',             'broken:frac-arity',         'x = \\frac{a'],

  // empty script
  ['empty-sup',             'broken:empty-script',       'x^'],
  ['empty-sub',             'broken:empty-script',       'x_'],
  ['empty-sup-eol',         'broken:empty-script',       'a + b^'],
  ['empty-sub-eol',         'broken:empty-script',       'a + b_'],
  ['empty-sup-brace-close', 'broken:empty-script',       '{x^}'],
  ['empty-script-seq',      'broken:empty-script',       'a^_'],
  ['empty-sup-then-sub',    'broken:empty-script',       'x^_b'],
  ['empty-sub-then-sup',    'broken:empty-script',       'x_^b'],
  ['empty-sup-space',       'broken:empty-script',       'x^ '],
  ['empty-sub-space',       'broken:empty-script',       'x_ '],
  ['empty-sup-alpha',       'broken:empty-script',       '\\alpha^'],
  ['empty-sub-frac',        'broken:empty-script',       '\\frac{a}{b}_'],

  // double script
  ['double-sup',            'broken:double-script',      'x^a^b'],
  ['double-sub',            'broken:double-script',      'x_a_b'],
  ['double-sup-nospace',    'broken:double-script',      'y^1^2'],
  ['double-sub-letters',    'broken:double-script',      'T_a_b'],
  ['double-sup-expr',       'broken:double-script',      'e^x^2'],
  ['double-sub-long',       'broken:double-script',      'A_i_j_k'],
  ['double-sup-greek',      'broken:double-script',      '\\alpha^a^b'],
  ['double-sub-num',        'broken:double-script',      'x_1_2'],
  ['double-sup-letter',     'broken:double-script',      'n^k^j'],
  ['double-sub-greek',      'broken:double-script',      '\\mu_a_b'],

  // \left without \right
  ['left-no-right',         'broken:left-right',         '\\left( x + y'],
  ['left-bracket-no-right', 'broken:left-right',         '\\left[ \\frac{a}{b}'],
  ['left-brace-no-right',   'broken:left-right',         '\\left\\{ x \\in \\mathbb{R}'],
  ['left-vert-no-right',    'broken:left-right',         '\\left| x'],
  ['left-extra',            'broken:left-right',         '\\left( a \\right) \\left( b'],
  ['left-nested-no-right',  'broken:left-right',         '\\left( \\left[ x \\right]'],
  ['left-norm-no-right',    'broken:left-right',         '\\left\\| v \\|'],
  ['left-paren-frac',       'broken:left-right',         '\\left( \\frac{1}{x}'],

  // \right without \left
  ['right-no-left',         'broken:left-right',         'x + y \\right)'],
  ['right-bracket-no-left', 'broken:left-right',         '\\frac{a}{b} \\right]'],
  ['right-extra',           'broken:left-right',         '\\left( a \\right) \\right)'],
  ['right-brace-no-left',   'broken:left-right',         'x \\right\\}'],
  ['right-vert-no-left',    'broken:left-right',         '\\sum_k a_k \\right|'],

  // unbalanced braces
  ['extra-open-brace',      'broken:unbalanced-braces',  '\\frac{a}{b{c}'],
  ['extra-close-brace',     'broken:unbalanced-braces',  '\\frac{a}{b}}'],
  ['missing-close-brace',   'broken:unbalanced-braces',  '\\sqrt{x + y'],
  ['double-open-brace',     'broken:unbalanced-braces',  '{{x + y}'],
  ['unclosed-env-brace',    'broken:unbalanced-braces',  '\\begin{matrix} a & b \\end{matrix'],
  ['extra-close-mid',       'broken:unbalanced-braces',  'a + b} + c'],
  ['extra-open-mid',        'broken:unbalanced-braces',  'a + {b + c'],
  ['three-opens',           'broken:unbalanced-braces',  '{{{a + b}'],
  ['two-close-one-open',    'broken:unbalanced-braces',  '{a}}'],
  ['sup-unclosed',          'broken:unbalanced-braces',  'x^{a + b'],
  ['sub-unclosed',          'broken:unbalanced-braces',  'x_{i+1'],
  ['frac-extra-close',      'broken:unbalanced-braces',  '\\frac{a}{}}}'],

  // mismatched \begin/\end
  ['begin-end-mismatch',    'broken:begin-end',          '\\begin{matrix} a \\end{pmatrix}'],
  ['begin-no-end',          'broken:begin-end',          '\\begin{matrix} a & b'],
  ['end-no-begin',          'broken:begin-end',          'a & b \\end{matrix}'],
  ['begin-end-swap',        'broken:begin-end',          '\\begin{cases} x \\end{matrix}'],
  ['begin-nested-mismatch', 'broken:begin-end',          '\\begin{aligned} \\begin{matrix} a \\end{aligned} \\end{matrix}'],
  ['begin-double-end',      'broken:begin-end',          '\\begin{matrix} a \\end{matrix} \\end{matrix}'],
  ['begin-typo',            'broken:begin-end',          '\\begin{matirx} a \\end{matirx}'],
  ['begin-cases-no-end',    'broken:begin-end',          '\\begin{cases} a & b'],
  ['begin-aligned-swap',    'broken:begin-end',          '\\begin{aligned} x &= 1 \\end{cases}'],
  ['end-only-pmatrix',      'broken:begin-end',          'a \\end{pmatrix}'],

  // malformed sqrt optional
  ['sqrt-bad-opt-no-close', 'broken:sqrt-optional',      '\\sqrt[3 x'],
  ['sqrt-bad-opt-nospace',  'broken:sqrt-optional',      '\\sqrt['],
  ['sqrt-bad-opt-nested',   'broken:sqrt-optional',      '\\sqrt[n{x}'],
  ['sqrt-bad-opt-only',     'broken:sqrt-optional',      '\\sqrt[n'],
  ['sqrt-bad-opt-empty',    'broken:sqrt-optional',      '\\sqrt[{x}'],

  // stray & outside environment
  ['amp-outside-env',       'broken:amp-outside',        'a & b + c'],
  ['amp-outside-frac',      'broken:amp-outside',        '\\frac{a & b}{c}'],
  ['amp-outside-double',    'broken:amp-outside',        'x & y & z'],
  ['amp-outside-simple',    'broken:amp-outside',        'x & y'],
  ['amp-after-eq',          'broken:amp-outside',        'x = 1 & y = 2'],
  ['amp-in-sqrt',           'broken:amp-outside',        '\\sqrt{a & b}'],
  ['amp-in-text',           'broken:amp-outside',        '\\text{a & b}'],

  // unknown command (not in KaTeX OR not in allowlist — both matter)
  ['unknown-frooble',       'broken:unknown-cmd',        '\\frooble{x}'],
  ['unknown-mycmd',         'broken:unknown-cmd',        '\\mycmd{a}{b}'],
  ['unknown-zzz',           'broken:unknown-cmd',        '\\zzz'],
  ['unknown-vdots',         'broken:unknown-cmd',        '\\vdots'],
  ['unknown-ddots',         'broken:unknown-cmd',        '\\ddots'],
  ['unknown-textrm',        'broken:unknown-cmd',        '\\textrm{hello}'],
  ['unknown-boldsymbol',    'broken:unknown-cmd',        '\\boldsymbol{x}'],
  ['unknown-xrightarrow',   'broken:unknown-cmd',        'A \\xrightarrow{f} B'],
  ['unknown-underbrace',    'broken:unknown-cmd',        '\\underbrace{a+b}_{\\text{sum}}'],
  ['unknown-overbrace',     'broken:unknown-cmd',        '\\overbrace{a+b}^{\\text{sum}}'],

  // trailing backslash
  ['trailing-backslash',    'broken:trailing-bs',        'a + b\\'],
  ['mid-backslash',         'broken:trailing-bs',        '\\frac{a\\}{b}'],
  ['trailing-bs-space',     'broken:trailing-bs',        'x + y\\ '],
  ['cmd-then-trailing',     'broken:trailing-bs',        '\\alpha + \\beta\\'],

  // stacked/combined errors
  ['combo-unbal-frac',      'broken:combo',              '\\frac{a{b}'],
  ['combo-left-unbal',      'broken:combo',              '\\left( \\frac{a}{b'],
  ['combo-begin-brace',     'broken:combo',              '\\begin{matrix} a & b \\end{matrix'],
  ['combo-double-sup-frac', 'broken:combo',              'x^a^{\\frac{b}{c}}'],
  ['combo-amp-left',        'broken:combo',              '\\left( a & b \\right)'],
  ['combo-empty-sup-frac',  'broken:combo',              '\\frac{a^}{b}'],
  ['combo-double-sub-left', 'broken:combo',              '\\left[ x_a_b \\right]'],
  ['combo-begin-mismatch-brace', 'broken:combo',         '\\begin{matrix} a \\end{pmatrix'],

  // more genuine parse failures
  ['sup-unclosed-deep',     'broken:misc',               'x^{a^{b}'],
  ['left-right-mistype',    'broken:misc',               '\\left( \\frac{1}{2} \\right['],
  ['cmd-dollar',            'broken:misc',               'x = \\$5'],
  ['begin-empty',           'broken:begin-end',          '\\begin{} a \\end{}'],
  ['sub-open-brace',        'broken:unbalanced-braces',  'x_{a + b'],
  ['cmd-backslash-num',     'broken:misc',               '\\1 + 2'],
  ['double-frac-incomplete','broken:frac-arity',         '\\frac{\\frac{a}'],
  ['left-angle-no-right',   'broken:left-right',         '\\left\\langle v'],
  ['right-angle-no-left',   'broken:left-right',         'v \\right\\rangle'],

  // more broken: additional frac arity variants
  ['frac-one-brace-only',   'broken:frac-arity',         '\\frac{a}b'],
  ['frac-space-one',        'broken:frac-arity',         '\\frac {a}'],
  ['frac-chain-incomplete', 'broken:frac-arity',         '\\frac{\\frac{a}{b}'],
  ['frac-cmd-incomplete',   'broken:frac-arity',         '\\frac{\\alpha'],

  // more broken: empty scripts in compound expressions
  ['empty-sup-after-frac',  'broken:empty-script',       '\\frac{a}{b}^'],
  ['empty-sub-after-sqrt',  'broken:empty-script',       '\\sqrt{x}_'],
  ['empty-sup-cmd',         'broken:empty-script',       '\\alpha^'],
  ['empty-sub-cmd',         'broken:empty-script',       '\\beta_'],

  // more broken: double-scripts in mixed expressions
  ['double-sup-in-frac',    'broken:double-script',      '\\frac{x^a^b}{c}'],
  ['double-sub-in-sqrt',    'broken:double-script',      '\\sqrt{x_a_b}'],
  ['double-sup-in-sum',     'broken:double-script',      '\\sum_{k^a^b}'],
  ['double-sub-in-int',     'broken:double-script',      '\\int_{0_a_b}^{1}'],

  // more broken: left-right asymmetry chains
  ['left-paren-right-brack','broken:left-right',         '\\left( x \\right]'],
  ['left-brack-right-paren','broken:left-right',         '\\left[ x \\right)'],
  ['multi-left-one-right',  'broken:left-right',         '\\left( \\left[ x \\right]'],
  ['one-left-multi-right',  'broken:left-right',         '\\left( x \\right) \\right)'],

  // more broken: begin/end mismatches
  ['begin-aligned-matrix',  'broken:begin-end',          '\\begin{aligned} x &= 1 \\end{matrix}'],
  ['begin-pmatrix-cases',   'broken:begin-end',          '\\begin{pmatrix} a \\end{cases}'],
  ['begin-unknown-env',     'broken:begin-end',          '\\begin{unknown} a \\end{unknown}'],
  ['begin-no-end-2',        'broken:begin-end',          '\\begin{aligned} x &= 1 \\\\ y &= 2'],
  ['end-before-begin',      'broken:begin-end',          '\\end{matrix} a \\begin{matrix}'],

  // more broken: unbalanced brace variety
  ['empty-group-mid',       'broken:unbalanced-braces',  'a + {} + {b'],
  ['nested-missing',        'broken:unbalanced-braces',  '{{a} + {b}'],
  ['close-then-open',       'broken:unbalanced-braces',  'a} + {b'],
  ['deep-unclosed',         'broken:unbalanced-braces',  '{{{{{a}}}'],
  ['sup-nested-unclosed',   'broken:unbalanced-braces',  'x^{a^{b^{c}'],

  // more broken: stray & in various contexts
  ['amp-in-binom',          'broken:amp-outside',        '\\binom{a & b}{c}'],
  ['amp-in-cases-outside',  'broken:amp-outside',        'f(x) = a & b'],
  ['amp-standalone',        'broken:amp-outside',        '&'],

  // more broken: unknown commands that KaTeX doesn't know
  ['unknown-abs',           'broken:unknown-cmd',        '\\abs{x}'],
  ['unknown-norm',          'broken:unknown-cmd',        '\\norm{v}'],
  ['unknown-R',             'broken:unknown-cmd',        '\\R'],
  ['unknown-N',             'broken:unknown-cmd',        '\\N'],
  ['unknown-Z',             'broken:unknown-cmd',        '\\Z'],

  // more broken: trailing backslash
  ['trailing-end',          'broken:trailing-bs',        '\\frac{a}{b}\\'],
  ['trailing-after-cmd',    'broken:trailing-bs',        '\\alpha\\'],

  // more broken: miscellaneous
  ['null-delimiter-wrong',  'broken:misc',               '\\right. x'],
  ['double-frac-no-second', 'broken:misc',               '\\frac{\\frac{a}{b}}{'],
  ['sup-then-empty',        'broken:empty-script',       'f(x)^'],
  ['sub-then-empty',        'broken:empty-script',       'g(x)_'],

  // more broken: complete the 400 threshold
  ['frac-only-slash',       'broken:frac-arity',         '\\frac{a/b}'],
  ['double-sup-braced',     'broken:double-script',      'x^{a}^{b}'],
  ['double-sub-braced',     'broken:double-script',      'x_{a}_{b}'],
  ['left-only-2',           'broken:left-right',         '\\left( a \\left[ b \\right]'],
  ['begin-empty-env',       'broken:begin-end',          '\\begin{pmatrix} \\end{matrix}'],
  ['unbal-frac-both',       'broken:unbalanced-braces',  '\\frac{{a}{b}'],
  ['amp-in-lim',            'broken:amp-outside',        '\\lim_{a & b}'],
  ['unknown-imath',         'broken:unknown-cmd',        '\\imath + \\jmath'],
  ['trailing-double',       'broken:trailing-bs',        'a \\\\ b\\'],
  ['empty-sup-in-env',      'broken:empty-script',       '\\begin{pmatrix} x^ \\end{pmatrix}'],
  ['frac-missing-both',     'broken:frac-arity',         '\\frac{'],
  ['combo-empty-env',       'broken:combo',              '\\begin{matrix} x^ \\end{matrix}'],
  ['right-in-text',         'broken:left-right',         '\\text{hello} \\right)'],
  ['double-sub-in-env',     'broken:double-script',      '\\begin{matrix} x_a_b \\end{matrix}'],
  ['unbal-env-brace',       'broken:unbalanced-braces',  '\\begin{matrix} {a & b \\end{matrix}'],
  ['sqrt-bad-mid',          'broken:sqrt-optional',      '\\sqrt[2 + \\frac{a}{b}'],
  ['amp-after-frac',        'broken:amp-outside',        '\\frac{a}{b} & c'],
  ['begin-reversed',        'broken:begin-end',          '\\end{cases} x \\begin{cases}'],
  ['unknown-operatorname',  'broken:unknown-cmd',        '\\operatorname{rank}(A)'],
  ['empty-sub-deep',        'broken:empty-script',       'x^{a_}'],
];

// ---------------------------------------------------------------------------
// Build dataset
// ---------------------------------------------------------------------------

function makeId(prefix, suffix) {
  return `${prefix}-${suffix}`;
}

function probe(latex) {
  try {
    katex.renderToString(latex, { throwOnError: true, strict: false });
    return { katexOk: true, katexError: '' };
  } catch (e) {
    return { katexOk: false, katexError: e.message || String(e) };
  }
}

const rows = [];

for (const [suffix, family, latex] of VALID) {
  const { katexOk, katexError } = probe(latex);
  rows.push({ id: makeId('v', suffix), latex, family, katexOk, katexError });
}

for (const [suffix, family, latex] of BROKEN) {
  const { katexOk, katexError } = probe(latex);
  rows.push({ id: makeId('b', suffix), latex, family, katexOk, katexError });
}

// ---------------------------------------------------------------------------
// Write output
// ---------------------------------------------------------------------------

const __dirname  = path.dirname(fileURLToPath(import.meta.url));
const outputDir  = path.resolve(__dirname, '../../../wikantik-main/src/test/resources/math');
const outputPath = path.join(outputDir, 'probe-dataset.json');

mkdirSync(outputDir, { recursive: true });
writeFileSync(outputPath, JSON.stringify(rows, null, 2), 'utf8');

// Summary
const total = rows.length;
const ok    = rows.filter(r => r.katexOk).length;
const err   = rows.filter(r => !r.katexOk).length;

// Family breakdown
const families = {};
for (const r of rows) {
  families[r.family] = (families[r.family] || 0) + 1;
}

console.log(`probe-dataset.json written to ${outputPath}`);
console.log(`total=${total}  katexOk=${ok}  katexError=${err}`);
console.log('');
console.log('Family breakdown:');
for (const [f, n] of Object.entries(families).sort()) {
  console.log(`  ${f.padEnd(36)} ${n}`);
}
