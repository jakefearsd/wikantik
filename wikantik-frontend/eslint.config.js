import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import testingLibrary from 'eslint-plugin-testing-library';

export default [
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**'] },
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      parserOptions: { ecmaFeatures: { jsx: true } },
      globals: { ...globals.browser },
    },
    ...js.configs.recommended,
    rules: {
      ...js.configs.recommended.rules,
      // JSX component usage isn't visible to no-unused-vars; ignore
      // PascalCase/constant identifiers (Vite react-template convention).
      'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]', argsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['**/*.{js,jsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // React-Compiler-era advisory rules: valuable on new code, but the
      // pre-existing patterns (setState-in-effect for derived state, ref
      // conventions) are pervasive and behavior-correct — warn, don't fail.
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/refs': 'warn',
      'react-hooks/immutability': 'warn',
      'react-hooks/purity': 'warn',
      'react-hooks/use-memo': 'warn',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
  {
    files: ['**/*.test.{js,jsx}', 'src/test/**'],
    plugins: { 'testing-library': testingLibrary },
    languageOptions: {
      globals: { ...globals.node, vi: 'readonly', describe: 'readonly', it: 'readonly', expect: 'readonly',
                 beforeEach: 'readonly', afterEach: 'readonly', beforeAll: 'readonly', afterAll: 'readonly', test: 'readonly' },
    },
    rules: {
      ...testingLibrary.configs['flat/react'].rules,
      // This app renders raw markdown/HTML output, so asserting through
      // `container`/DOM nodes is often the honest way to test it. Keep the
      // async-correctness rules as errors; relax the query-style ones.
      'testing-library/no-node-access': 'off',
      'testing-library/no-container': 'off',
      'testing-library/render-result-naming-convention': 'off',
      'testing-library/prefer-screen-queries': 'off',
      'testing-library/prefer-find-by': 'warn',
      'testing-library/no-unnecessary-act': 'warn',
    },
  },
  {
    files: ['vite.config.js', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } },
  },
];
