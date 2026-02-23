/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{clj,cljs,html}",
    "./resources/docs/**/*.md"
  ],
  theme: {
    extend: {
      colors: {
        datalevin: {
          50: '#f0f9ff',
          100: '#e0f2fe',
          500: '#0ea5e9',
          600: '#0284c7',
          700: '#0369a1',
        }
      },
      typography: {
        DEFAULT: {
          css: {
            maxWidth: '65ch',
            color: '#333',
            a: {
              color: '#2563eb',
              '&:hover': {
                color: '#1d4ed8',
              },
            },
            h1: {
              fontWeight: '700',
            },
            h2: {
              fontWeight: '600',
            },
            code: {
              backgroundColor: '#f5f5f5',
              padding: '0.125rem 0.25rem',
              borderRadius: '0.125rem',
            },
            'code::before': {
              content: '""',
            },
            'code::after': {
              content: '""',
            },
          },
        },
      },
    },
  },
  plugins: [],
}
