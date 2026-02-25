/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{clj,cljs,html}",
    "./resources/docs/**/*.md"
  ],
  safelist: [
    'prose', 'prose-lg', 'prose-xl', 'prose-datalevin', 'max-w-none',
    'bg-white', 'bg-gray-50', 'bg-gray-900', 'bg-blue-50', 'bg-blue-600', 'bg-red-50', 'bg-green-50',
    'text-gray-900', 'text-gray-700', 'text-gray-600', 'text-gray-500', 'text-gray-400', 'text-gray-300',
    'text-blue-600', 'text-blue-700', 'text-blue-800', 'text-red-700', 'text-green-700', 'text-white',
    'text-cyan-400', 'text-cyan-300',
    'border-gray-200', 'border-gray-300', 'border-blue-100',
    'rounded-lg', 'rounded-xl', 'rounded-md',
    'shadow-sm', 'shadow-lg',
    'hover:bg-blue-700', 'hover:bg-gray-50', 'hover:bg-gray-800', 'hover:text-blue-600', 'hover:text-cyan-400', 'hover:underline',
    'focus:ring-2', 'focus:ring-cyan-500', 'focus:ring-blue-500', 'focus:border-blue-500', 'focus:border-cyan-500',
    'font-bold', 'font-medium', 'font-mono',
    'text-xs', 'text-sm', 'text-base', 'text-lg', 'text-xl', 'text-2xl', 'text-3xl', 'text-4xl', 'text-5xl',
    'px-2', 'px-3', 'px-4', 'px-6', 'px-8', 'py-1', 'py-2', 'py-3', 'py-6', 'py-8', 'py-12', 'py-16',
    'mx-auto', 'ml-2', 'mr-2', 'mb-1', 'mb-2', 'mb-4', 'mb-6', 'mb-8', 'mt-2', 'mt-4', 'mt-6', 'mt-8', 'mt-12',
    'gap-2', 'gap-3', 'gap-4', 'gap-6',
    'space-y-4',
    'max-w-md', 'max-w-2xl', 'max-w-3xl', 'max-w-4xl', 'max-w-5xl',
    'min-h-screen',
    'flex', 'flex-1', 'flex-col', 'items-center', 'justify-center', 'justify-between',
    'grid', 'grid-cols-1', 'grid-cols-2', 'grid-cols-3',
    'w-full', 'h-8', 'h-24', 'w-8', 'w-24',
    'sticky', 'top-0', 'z-50',
    'fixed', 'relative', 'absolute',
    'hidden', 'block', 'inline',
    'overflow-hidden', 'overflow-x-auto',
    'cursor-pointer', 'outline-none',
    'transition', 'duration-200',
    'border', 'border-b', 'border-t',
    'opacity-50', 'opacity-75',
    'bg-gradient-to-r', 'from-blue-500', 'to-indigo-600',
    'from-cyan-400', 'to-blue-500',
    'backdrop-blur-xl', 'backdrop-blur-md',
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
        datalevin: {
          css: {
            '--tw-prose-body': '#d1d5db',
            '--tw-prose-headings': '#f9fafb',
            '--tw-prose-links': '#22d3ee',
            '--tw-prose-bold': '#f9fafb',
            '--tw-prose-code': '#f9fafb',
            '--tw-prose-pre-code': '#e5e7eb',
            '--tw-prose-pre-bg': '#0d0d14',
            maxWidth: 'none',
            fontSize: '1.0625rem',
            lineHeight: '1.8',
            color: '#d1d5db',
            p: {
              marginTop: '1.25em',
              marginBottom: '1.25em',
            },
            a: {
              color: '#22d3ee',
              textDecoration: 'underline',
              textDecorationColor: 'rgba(34,211,238,0.4)',
              textUnderlineOffset: '3px',
              '&:hover': {
                color: '#67e8f9',
                textDecorationColor: '#67e8f9',
              },
            },
            h1: {
              fontWeight: '700',
              fontSize: '1.5em',
              marginTop: '0',
              marginBottom: '0.8em',
              lineHeight: '1.2',
              letterSpacing: '-0.02em',
              color: '#f9fafb',
            },
            h2: {
              fontWeight: '700',
              fontSize: '1.5em',
              marginTop: '2em',
              marginBottom: '0.75em',
              paddingBottom: '0.3em',
              borderBottom: '1px solid rgba(255,255,255,0.1)',
              lineHeight: '1.3',
              color: '#f9fafb',
            },
            h3: {
              fontWeight: '600',
              fontSize: '1.25em',
              marginTop: '1.6em',
              marginBottom: '0.6em',
              lineHeight: '1.4',
              color: '#f9fafb',
            },
            h4: {
              color: '#f9fafb',
            },
            'ul > li': {
              paddingLeft: '0.375em',
            },
            'ol > li': {
              paddingLeft: '0.375em',
            },
            'ul > li::marker': {
              color: '#6b7280',
            },
            'ol > li::marker': {
              color: '#6b7280',
            },
            blockquote: {
              fontStyle: 'normal',
              borderLeftWidth: '3px',
              borderLeftColor: '#06b6d4',
              backgroundColor: 'rgba(255,255,255,0.03)',
              padding: '0.75em 1em',
              borderRadius: '0 0.375rem 0.375rem 0',
              color: '#d1d5db',
            },
            'blockquote p:first-of-type::before': {
              content: 'none',
            },
            'blockquote p:last-of-type::after': {
              content: 'none',
            },
            code: {
              backgroundColor: 'rgba(255,255,255,0.1)',
              padding: '0.2em 0.4em',
              borderRadius: '0.25rem',
              fontSize: '0.875em',
              fontWeight: '500',
              color: '#f9fafb',
            },
            'code::before': {
              content: '""',
            },
            'code::after': {
              content: '""',
            },
            pre: {
              backgroundColor: '#0d0d14',
              borderRadius: '0.5rem',
              padding: '1.25em 1.5em',
              overflow: 'auto',
              border: '1px solid rgba(255,255,255,0.1)',
            },
            'pre code': {
              backgroundColor: 'transparent',
              padding: '0',
              fontSize: '0.875em',
              fontWeight: '400',
              color: '#e5e7eb',
            },
            strong: {
              fontWeight: '600',
              color: '#f9fafb',
            },
            hr: {
              borderColor: 'rgba(255,255,255,0.1)',
              marginTop: '3em',
              marginBottom: '3em',
            },
            table: {
              fontSize: '0.9375em',
            },
            'thead th': {
              fontWeight: '600',
              borderBottomWidth: '2px',
              borderBottomColor: 'rgba(255,255,255,0.15)',
              color: '#f9fafb',
            },
            'tbody td': {
              borderBottomColor: 'rgba(255,255,255,0.08)',
            },
            'tbody tr': {
              borderBottomColor: 'rgba(255,255,255,0.08)',
            },
          },
        },
      },
    },
  },
  plugins: [
    require('@tailwindcss/typography'),
  ],
}
