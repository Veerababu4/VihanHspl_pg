import { useState } from 'react'
import { useTranslation } from 'react-i18next'

/**
 * One scrolling line at the top of every public page: this is a test project, not a real hospital
 * (shown while `app-info.testNotice` is on, i.e. EMAIL_TEST_BANNER). Accessible moving text
 * (WCAG 2.2.2): a Pause button, it also stops on hover / keyboard focus, and with "reduce motion"
 * it doesn't move at all. The text is read once; the second copy (for the seamless loop) is hidden.
 */
export function TestSiteMarquee() {
  const { t } = useTranslation()
  const [paused, setPaused] = useState(false)
  const text = t('banner.testSite')

  return (
    <div role="note" aria-label={t('banner.testSiteLabel')} className="flex items-center gap-2 bg-red-700 text-white print:hidden">
      <div className={`marquee min-w-0 flex-1 overflow-hidden py-1.5 text-sm font-semibold ${paused ? 'marquee-paused' : ''}`}>
        <div className="marquee-track">
          <span className="px-8">{text}</span>
          <span className="px-8" aria-hidden="true">
            {text}
          </span>
        </div>
      </div>
      <button
        type="button"
        aria-pressed={paused}
        onClick={() => setPaused((p) => !p)}
        className="marquee-toggle mr-2 shrink-0 rounded border border-white/60 px-2 py-0.5 text-xs font-medium hover:bg-white/15 focus-visible:outline-2 focus-visible:outline-white"
      >
        {paused ? t('banner.play') : t('banner.pause')}
      </button>
    </div>
  )
}
