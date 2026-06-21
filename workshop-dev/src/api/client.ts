import type { ResourceType, QueryDSL, QueryResult, SavedQuery, Role, Action } from '@/lib/types';

export interface ApiClient {
  schemaRegistry: {
    listTypes(): Promise<ResourceType[]>;
    getType(name: string): Promise<ResourceType>;
    createType(type: ResourceType): Promise<void>;
    updateType(name: string, type: ResourceType): Promise<void>;
    deleteType(name: string): Promise<void>;
  };
  query: {
    execute(query: QueryDSL): Promise<QueryResult>;
    history(): Promise<SavedQuery[]>;
    save(query: SavedQuery): Promise<void>;
    delete(id: string): Promise<void>;
  };
  security: {
    listRoles(): Promise<Role[]>;
    createRole(role: Role): Promise<void>;
    updateRole(name: string, role: Role): Promise<void>;
    deleteRole(name: string): Promise<void>;
    listActions(): Promise<Action[]>;
    createAction(action: Action): Promise<void>;
    updateAction(name: string, action: Action): Promise<void>;
    deleteAction(name: string): Promise<void>;
  };
}
