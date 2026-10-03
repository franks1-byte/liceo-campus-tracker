-- Liceo Campus Tracker: database setup (already applied to the live Supabase project).
-- Roles: guests (not signed in) can read; students add places and edit their own;
-- admins can edit or delete anything and change roles. Everyone registers as a student; an account
-- becomes admin by entering the admin code (only its hash is stored, in private.settings).

create schema if not exists private;

create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  full_name text,
  email text,
  role text not null default 'student' check (role in ('admin','student')),
  created_at timestamptz not null default now()
);

create table public.buildings (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 120),
  code text,
  kind text not null default 'academic',
  description text,
  floors int check (floors between 1 and 60),
  lat double precision not null check (lat between -90 and 90),
  lng double precision not null check (lng between -180 and 180),
  photo_path text,
  created_by uuid default auth.uid() references auth.users on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.rooms (
  id uuid primary key default gen_random_uuid(),
  building_id uuid not null references public.buildings on delete cascade,
  name text not null check (char_length(name) between 1 and 120),
  kind text not null default 'classroom',
  floor int,
  capacity int check (capacity >= 0),
  notes text,
  photo_path text,
  created_by uuid default auth.uid() references auth.users on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index rooms_building_idx on public.rooms (building_id);
create index rooms_created_by_idx on public.rooms (created_by);
create index buildings_created_by_idx on public.buildings (created_by);

-- helpers live in a private schema so they are not reachable through the public API
create function private.is_admin() returns boolean
language sql stable security definer set search_path = '' as
$$ select exists (select 1 from public.profiles where id = auth.uid() and role = 'admin') $$;

create function private.my_role() returns text
language sql stable security definer set search_path = '' as
$$ select role from public.profiles where id = auth.uid() $$;

create function private.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as
$$
begin
  insert into public.profiles (id, full_name, email, role)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'full_name', split_part(new.email, '@', 1)),
    new.email,
    'student' -- admin is granted only through claim_admin() below
  );
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
for each row execute function private.handle_new_user();

create function private.touch_updated_at() returns trigger
language plpgsql set search_path = '' as
$$ begin new.updated_at = now(); return new; end $$;

create trigger buildings_touch before update on public.buildings
for each row execute function private.touch_updated_at();
create trigger rooms_touch before update on public.rooms
for each row execute function private.touch_updated_at();

grant usage on schema private to authenticated, anon;
grant execute on function private.is_admin(), private.my_role() to authenticated, anon;

alter table public.profiles enable row level security;
alter table public.buildings enable row level security;
alter table public.rooms enable row level security;

grant select on public.buildings, public.rooms to anon, authenticated;
grant insert, update, delete on public.buildings, public.rooms to authenticated;
grant select on public.profiles to authenticated;
grant update (full_name, role) on public.profiles to authenticated;

create policy "anyone can view buildings" on public.buildings for select to anon, authenticated using (true);
create policy "signed-in users add buildings" on public.buildings for insert to authenticated
  with check (created_by = (select auth.uid()));
create policy "owner or admin edits buildings" on public.buildings for update to authenticated
  using (created_by = (select auth.uid()) or (select private.is_admin()))
  with check (created_by = (select auth.uid()) or (select private.is_admin()));
create policy "owner or admin deletes buildings" on public.buildings for delete to authenticated
  using (created_by = (select auth.uid()) or (select private.is_admin()));

create policy "anyone can view rooms" on public.rooms for select to anon, authenticated using (true);
create policy "signed-in users add rooms" on public.rooms for insert to authenticated
  with check (created_by = (select auth.uid()));
create policy "owner or admin edits rooms" on public.rooms for update to authenticated
  using (created_by = (select auth.uid()) or (select private.is_admin()))
  with check (created_by = (select auth.uid()) or (select private.is_admin()));
create policy "owner or admin deletes rooms" on public.rooms for delete to authenticated
  using (created_by = (select auth.uid()) or (select private.is_admin()));

create policy "read own profile or admin reads all" on public.profiles for select to authenticated
  using (id = (select auth.uid()) or (select private.is_admin()));
-- users may rename themselves but cannot change their own role; admins may change roles
create policy "update own name or admin updates" on public.profiles for update to authenticated
  using (id = (select auth.uid()) or (select private.is_admin()))
  with check ((select private.is_admin()) or (id = (select auth.uid()) and role = (select private.my_role())));

-- photo storage: public bucket, each user uploads into a folder named after their user id
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('photos', 'photos', true, 5242880, array['image/jpeg','image/png','image/webp']);

create policy "signed-in users upload photos" on storage.objects for insert to authenticated
  with check (bucket_id = 'photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "owner or admin deletes photos" on storage.objects for delete to authenticated
  using (bucket_id = 'photos' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select private.is_admin())));
create policy "owner or admin sees photo records" on storage.objects for select to authenticated
  using (bucket_id = 'photos' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select private.is_admin())));

-- admin registration: a signed-in user becomes admin by giving the correct admin code
create extension if not exists pgcrypto with schema extensions;
create table private.settings (key text primary key, value text not null);
create table private.admin_attempts (user_id uuid primary key references auth.users on delete cascade, fails int not null default 0);
alter table private.settings enable row level security;
alter table private.admin_attempts enable row level security;
-- set the code once, in the Supabase SQL editor (never commit the real code):
--   insert into private.settings values ('admin_code_hash', extensions.crypt('YOUR-ADMIN-CODE', extensions.gen_salt('bf', 10)));

create function public.claim_admin(code text) returns boolean
language plpgsql security definer set search_path = '' as
$$
declare h text; f int;
begin
  if auth.uid() is null then return false; end if;
  select fails into f from private.admin_attempts where user_id = auth.uid();
  if coalesce(f, 0) >= 5 then return false; end if; -- locked after 5 wrong tries
  select value into h from private.settings where key = 'admin_code_hash';
  if h is not null and code is not null and extensions.crypt(code, h) = h then
    update public.profiles set role = 'admin' where id = auth.uid();
    return true;
  end if;
  insert into private.admin_attempts (user_id, fails) values (auth.uid(), 1)
  on conflict (user_id) do update set fails = private.admin_attempts.fails + 1;
  return false;
end $$;
revoke execute on function public.claim_admin(text) from public, anon;
grant execute on function public.claim_admin(text) to authenticated;
