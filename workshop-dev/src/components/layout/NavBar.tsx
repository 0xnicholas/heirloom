import { NavLink } from 'react-router-dom';

const tabs = [
  { to: '/schema', label: 'Schema' },
  { to: '/query', label: 'Query' },
  { to: '/security', label: 'Security' },
];

export function NavBar() {
  return (
    <nav className="flex items-center gap-1 px-4 py-2 border-b border-gray-200 bg-white">
      <span className="font-bold text-lg mr-6 text-indigo-600">◇ Heirloom</span>
      {tabs.map(tab => (
        <NavLink
          key={tab.to}
          to={tab.to}
          className={({ isActive }) =>
            `px-4 py-1.5 rounded-md text-sm font-medium transition-colors ${
              isActive
                ? 'bg-indigo-100 text-indigo-700'
                : 'text-gray-600 hover:bg-gray-100'
            }`
          }
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
