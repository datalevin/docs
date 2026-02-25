/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{clj,cljs,html}",
    "./resources/docs/**/*.md"
  ],
  safelist: [
    'prose', 'prose-lg', 'prose-xl', 'prose-datalevin', 'max-w-none',
    'bg-white', 'bg-gray-50', 'bg-gray-900', 'bg-blue-50', 'bg-blue-600', 'bg-red-50', 'bg-green-50',
    'text-gray-900', 'text-gray-700', 'text-gray-600', 'text-gray-500', 'text-blue-600', 'text-blue-700', 'text-blue-800', 'text-red-700', 'text-green-700', 'text-white',
    'border-gray-200', 'border-gray-300', 'border-blue-100',
    'rounded-lg', 'rounded-xl', 'rounded-md',
    'shadow-sm', 'shadow-lg',
    'hover:bg-blue-700', 'hover:bg-gray-50', 'hover:bg-gray-800', 'hover:text-blue-600', 'hover:underline',
    'focus:ring-2', 'focus:ring-blue-500', 'focus:border-blue-500',
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
            '--tw-prose-body': '#374151',
            '--tw-prose-headings': '#111827',
            '--tw-prose-links': '#2563eb',
            '--tw-prose-bold': '#111827',
            '--tw-prose-code': '#111827',
            '--tw-prose-pre-code': '#e5e7eb',
            '--tw-prose-pre-bg': '#1f2937',
            maxWidth: 'none',
            fontSize: '1.0625rem',
            lineHeight: '1.8',
            p: {
              marginTop: '1.25em',
              marginBottom: '1.25em',
            },
            a: {
              color: '#2563eb',
              textDecoration: 'underline',
              textDecorationColor: '#93c5fd',
              textUnderlineOffset: '3px',
              '&:hover': {
                color: '#1d4ed8',
                textDecorationColor: '#1d4ed8',
              },
            },
            h1: {
              fontWeight: '700',
              fontSize: '1.5em',
              marginTop: '0',
              marginBottom: '0.8em',
              lineHeight: '1.2',
              letterSpacing: '-0.02em',
            },
            h2: {
              fontWeight: '700',
              fontSize: '1.5em',
              marginTop: '2em',
              marginBottom: '0.75em',
              paddingBottom: '0.3em',
              borderBottom: '1px solid #e5e7eb',
              lineHeight: '1.3',
            },
            h3: {
              fontWeight: '600',
              fontSize: '1.25em',
              marginTop: '1.6em',
              marginBottom: '0.6em',
              lineHeight: '1.4',
            },
            'ul > li': {
              paddingLeft: '0.375em',
            },
            'ol > li': {
              paddingLeft: '0.375em',
            },
            blockquote: {
              fontStyle: 'normal',
              borderLeftWidth: '3px',
              borderLeftColor: '#2563eb',
              backgroundColor: '#f8fafc',
              padding: '0.75em 1em',
              borderRadius: '0 0.375rem 0.375rem 0',
            },
            'blockquote p:first-of-type::before': {
              content: 'none',
            },
            'blockquote p:last-of-type::after': {
              content: 'none',
            },
            code: {
              backgroundColor: '#f3f4f6',
              padding: '0.2em 0.4em',
              borderRadius: '0.25rem',
              fontSize: '0.875em',
              fontWeight: '500',
            },
            'code::before': {
              content: '""',
            },
            'code::after': {
              content: '""',
            },
            pre: {
              backgroundColor: '#1f2937',
              borderRadius: '0.5rem',
              padding: '1.25em 1.5em',
              overflow: 'auto',
              border: '1px solid #374151',
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
            },
            hr: {
              borderColor: '#e5e7eb',
              marginTop: '3em',
              marginBottom: '3em',
            },
            table: {
              fontSize: '0.9375em',
            },
            'thead th': {
              fontWeight: '600',
              borderBottomWidth: '2px',
              borderBottomColor: '#d1d5db',
            },
            'tbody td': {
              borderBottomColor: '#e5e7eb',
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
