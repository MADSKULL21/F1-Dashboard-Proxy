import { Monitor, Moon, Sun } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { ThemePreference } from '@/theme/theme-context'
import { useTheme } from '@/theme/use-theme'

const OPTIONS: ReadonlyArray<{ value: ThemePreference; label: string; Icon: typeof Sun }> = [
  { value: 'light', label: 'Light', Icon: Sun },
  { value: 'dark', label: 'Dark', Icon: Moon },
  { value: 'system', label: 'System', Icon: Monitor },
]

export function ThemeToggle() {
  const { preference, resolved, setPreference } = useTheme()
  const ActiveIcon = resolved === 'dark' ? Moon : Sun

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          // The icon alone conveys nothing to a screen reader, and the current
          // choice is not visible from the trigger, so both go in the label.
          aria-label={`Theme: ${preference}. Change theme`}
        >
          <ActiveIcon className="h-4 w-4" aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {OPTIONS.map(({ value, label, Icon }) => (
          <DropdownMenuItem
            key={value}
            onSelect={() => setPreference(value)}
            // Communicates the active option to assistive tech, which a visual
            // checkmark alone would not.
            aria-current={preference === value}
          >
            <Icon className="mr-2 h-4 w-4" aria-hidden="true" />
            {label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
