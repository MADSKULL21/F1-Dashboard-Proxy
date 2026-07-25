import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useHealthQuery } from '@/store/api'

/**
 * Exists to prove the deployed frontend can reach the deployed backend through
 * CORS — the thing most likely to be quietly broken in a two-host setup.
 *
 * It is also the reference implementation of the three states PRD 9 requires:
 * loading (skeleton), error (only when there is genuinely nothing), and data.
 * F1 replaces it with the real standings view.
 */
export function ApiStatus() {
  const { data, isLoading, isError } = useHealthQuery()

  return (
    <Card>
      <CardHeader>
        <CardTitle>Backend connectivity</CardTitle>
        <CardDescription>
          Confirms the browser can reach the API and that CORS is configured correctly.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          // role="status" is an implicit polite live region, so a screen reader
          // announces arrival rather than content silently swapping in.
          <div role="status" aria-busy="true" className="space-y-2">
            <span className="sr-only">Checking backend status</span>
            <Skeleton className="h-5 w-24" />
            <Skeleton className="h-4 w-40" />
          </div>
        ) : isError ? (
          <Alert variant="destructive">
            <AlertTitle>API unreachable</AlertTitle>
            <AlertDescription>
              The backend did not respond. If you are running locally, start it with{' '}
              <code className="font-mono">./mvnw spring-boot:run</code> in <code>backend/</code>.
            </AlertDescription>
          </Alert>
        ) : (
          <dl className="space-y-2 text-sm">
            <div className="flex items-center gap-2">
              <dt className="text-muted-foreground">Status</dt>
              <dd>
                <Badge>{data?.status}</Badge>
              </dd>
            </div>
            <div className="flex items-center gap-2">
              <dt className="text-muted-foreground">Version</dt>
              <dd className="font-mono">{data?.version}</dd>
            </div>
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
