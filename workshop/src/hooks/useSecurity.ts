import { useActions } from './useActions';
import { useRoles } from './useRoles';

export function useSecurity() {
  const {
    actionsQuery,
    saveActionMutation,
    deleteActionMutation,
  } = useActions();

  const {
    rolesQuery,
    saveRoleMutation,
    deleteRoleMutation,
  } = useRoles();

  return {
    actionsQuery,
    rolesQuery,
    saveActionMutation,
    deleteActionMutation,
    saveRoleMutation,
    deleteRoleMutation,
  };
}
