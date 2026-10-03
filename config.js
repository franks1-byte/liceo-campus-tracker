// Public client settings. The publishable key is safe to ship in the browser:
// what each visitor may read or change is enforced by the database policies
// in supabase/schema.sql, not by keeping this key secret.
window.APP_CONFIG = {
  SUPABASE_URL: 'https://iljibsddirnnnkhnpqjb.supabase.co',
  SUPABASE_KEY: 'sb_publishable_X-LYjE0XrYp1KevQAu8HaQ_jRGZmx24',
  // Liceo de Cagayan University, main campus (Kauswagan, Cagayan de Oro)
  CAMPUS: { name: 'Liceo de Cagayan University', lat: 8.48581, lng: 124.63937, zoom: 18 }
};
