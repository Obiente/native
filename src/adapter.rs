use crate::{DiscoverySnapshot, NativeAppSchema};

/// Verified knowledge can improve an inferred schema, but all bindings still
/// have to point at operations discovered from the connected server.
pub trait Adapter: Send + Sync {
    fn id(&self) -> &'static str;
    fn supports(&self, snapshot: &DiscoverySnapshot) -> bool;
    fn enhance(&self, snapshot: &DiscoverySnapshot, schema: &mut NativeAppSchema);
}

#[derive(Default)]
pub struct AdapterRegistry {
    adapters: Vec<Box<dyn Adapter>>,
}

impl AdapterRegistry {
    pub fn register<A>(&mut self, adapter: A)
    where
        A: Adapter + 'static,
    {
        self.adapters.push(Box::new(adapter));
    }

    pub fn enhance(&self, snapshot: &DiscoverySnapshot, schema: &mut NativeAppSchema) {
        for adapter in &self.adapters {
            if adapter.supports(snapshot) {
                adapter.enhance(snapshot, schema);
            }
        }
    }
}
