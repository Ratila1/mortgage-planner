import { useState } from 'react';
import { AdminOffersPage } from './AdminOffersPage';
import { AdminMicroloanTypesPage } from './AdminMicroloanTypesPage';
import { AdminMicroloanApplicationsPage } from './AdminMicroloanApplicationsPage';
import { AdminUsersPage } from './AdminUsersPage';
import { PageTitle, Segmented, Card } from '../components/ui';

type AdminTab = 'offers' | 'microloan-types' | 'microloan-applications' | 'users';

const TABS: Array<{ value: AdminTab; label: string }> = [
  { value: 'offers', label: 'Предложения банков' },
  { value: 'microloan-types', label: 'Типы микрозаймов' },
  { value: 'microloan-applications', label: 'Заявки микрозаймов' },
  { value: 'users', label: 'Пользователи' },
];

export function AdminPanelPage() {
  const [activeTab, setActiveTab] = useState<AdminTab>('offers');

  return (
    <div className="space-y-8">
      <PageTitle eyebrow="Панель админа" title="Управление" />
      
      <div className="mb-4">
        <div className="text-sm font-medium text-ink-2 mb-2">Разделы</div>
        <div className="flex flex-wrap gap-2">
          {TABS.map((tab) => (
            <button
              key={tab.value}
              type="button"
              onClick={() => setActiveTab(tab.value)}
              className={`rounded-lg px-3 py-2 text-sm font-medium transition ${
                activeTab === tab.value
                  ? 'bg-money-solid text-white shadow-sm'
                  : 'bg-paper-2 text-ink-2 hover:bg-ink/10 hover:text-ink'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <Card>
        {activeTab === 'offers' && <AdminOffersPage />}
        {activeTab === 'microloan-types' && <AdminMicroloanTypesPage />}
        {activeTab === 'microloan-applications' && <AdminMicroloanApplicationsPage />}
        {activeTab === 'users' && <AdminUsersPage />}
      </Card>
    </div>
  );
}