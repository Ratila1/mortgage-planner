import { Link } from 'react-router';
import { Stamp } from '../components/ui';

export function NotFoundPage() {
  return (
    <div className="reveal mx-auto max-w-lg py-16 text-center">
      <Stamp className="mb-6">404</Stamp>
      <h1 className="font-display text-3xl font-bold">Страница погашена досрочно</h1>
      <p className="mt-3 text-ink-2">Её здесь больше нет. В отличие от процентов по ипотеке, которые никуда не денутся.</p>
      <Link to="/" viewTransition className="mt-8 inline-block rounded-xl bg-money-solid px-5 py-3 text-sm font-semibold text-white hover:bg-money-solid-2 transition-colors">
        К калькулятору
      </Link>
    </div>
  );
}
