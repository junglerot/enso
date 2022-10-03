//! A module containing a controller for the breadcrumbs panel of the component browser.

use crate::prelude::*;

use crate::controller::searcher::component;

use double_representation::module;
use model::suggestion_database::entry::QualifiedName;
use model::suggestion_database::Entry;



// ===================
// === Breadcrumbs ===
// ===================

/// A controller that keeps the path of entered modules in the Searcher and provides the
/// functionality of the breadcrumbs panel. The integration between the
/// controller and the view is done by the [searcher presenter](crate::presenter::searcher).
#[derive(Debug, Clone, CloneRef, Default)]
pub struct Breadcrumbs {
    list:     Rc<RefCell<Vec<BreadcrumbEntry>>>,
    selected: Rc<Cell<usize>>,
}

impl Breadcrumbs {
    /// Constructor.
    pub fn new() -> Self {
        default()
    }

    /// Set the list of breadcrumbs to be displayed in the breadcrumbs panel.
    pub fn set_content(&self, breadcrumbs: impl Iterator<Item = BreadcrumbEntry>) {
        let mut borrowed = self.list.borrow_mut();
        *borrowed = breadcrumbs.collect();
        self.select(borrowed.len());
    }

    /// A list of breadcrumbs' text labels to be displayed in the panel.
    pub fn names(&self) -> Vec<ImString> {
        self.list.borrow().iter().map(|entry| entry.name()).collect()
    }

    /// The last (right-most) breadcrumb in the list.
    pub fn last(&self) -> Option<component::Id> {
        self.list.borrow().last().map(BreadcrumbEntry::id)
    }

    /// Mark the entry with the given index as selected.
    pub fn select(&self, id: usize) {
        self.selected.set(id);
    }

    /// Returns true if the currently selected breadcrumb is the first one.
    pub fn is_top_module(&self) -> bool {
        self.selected.get() == 0
    }

    /// Returns a currently selected breadcrumb id. Returns [`None`] if the top level breadcrumb
    /// is selected.
    pub fn selected(&self) -> Option<component::Id> {
        if self.is_top_module() {
            None
        } else {
            let index = self.selected.get();
            self.list.borrow().get(index - 1).map(BreadcrumbEntry::id)
        }
    }
}



// =======================
// === BreadcrumbEntry ===
// =======================

/// A single entry in the breadcrumbs panel.
#[derive(Debug, Clone)]
pub struct BreadcrumbEntry {
    displayed_name: ImString,
    component_id:   component::Id,
    qualified_name: QualifiedName,
}

impl BreadcrumbEntry {
    /// A displayed label of the entry.
    pub fn name(&self) -> ImString {
        self.displayed_name.clone_ref()
    }

    /// A component id of the entry.
    pub fn id(&self) -> component::Id {
        self.component_id
    }

    /// A qualified name of the entry.
    pub fn qualified_name(&self) -> &QualifiedName {
        &self.qualified_name
    }
}

impl From<(component::Id, Rc<Entry>)> for BreadcrumbEntry {
    fn from((component_id, entry): (component::Id, Rc<Entry>)) -> Self {
        let qualified_name = entry.qualified_name();
        let displayed_name = ImString::new(&entry.name);
        BreadcrumbEntry { displayed_name, component_id, qualified_name }
    }
}



// ===============
// === Builder ===
// ===============

/// A builder for the breadcrumbs list. It is used to include all parent modules when pushing the
/// new breadcrumb to the panel.
#[derive(Debug)]
pub struct Builder<'a> {
    database:   &'a model::SuggestionDatabase,
    components: component::List,
}

impl<'a> Builder<'a> {
    /// Constructor.
    pub fn new(database: &'a model::SuggestionDatabase, components: component::List) -> Self {
        Self { database, components }
    }

    /// Build a list of breadcrumbs for a specified module. The list will contain:
    /// 1. The main module of the project.
    /// 2. All parent modules of the [`module`].
    /// 3. The [`module`] itself.
    ///
    /// Returns an empty vector if the [`module`] is not found in the database or in the
    /// components list.
    pub fn build(self, module: &component::Id) -> Box<dyn Iterator<Item = BreadcrumbEntry>> {
        let (module_name, entry) = match self.module_name_and_entry(module) {
            Some(name_and_entry) => name_and_entry,
            None => return Box::new(iter::empty()),
        };
        let project_name = module_name.project_name.clone();
        let main_module_name = module::QualifiedName::new_main(project_name.clone());
        let main_module = self.lookup(&main_module_name);
        let to_main_module_entry = |entry: (component::Id, Rc<Entry>)| BreadcrumbEntry {
            displayed_name: String::from(project_name.project).into(),
            ..entry.into()
        };
        let main_module = main_module.map(to_main_module_entry).into_iter();
        let parents = self.collect_parents(&module_name);
        let iter = iter::once(entry).chain(parents).chain(main_module).rev();
        Box::new(iter)
    }

    fn module_name_and_entry(
        &self,
        module: &component::Id,
    ) -> Option<(Rc<module::QualifiedName>, BreadcrumbEntry)> {
        let module_name = self.components.module_qualified_name(*module)?;
        let entry = BreadcrumbEntry::from(self.lookup(&module_name)?);
        Some((module_name, entry))
    }

    fn lookup(&self, name: &module::QualifiedName) -> Option<(component::Id, Rc<Entry>)> {
        self.database.lookup_by_qualified_name(name.into_iter())
    }

    /// Collect all parent modules of the given module.
    ///
    /// Panics if the module is not found in the database.
    fn collect_parents(&self, name: &module::QualifiedName) -> Vec<BreadcrumbEntry> {
        let parents = name.parent_modules();
        let database_entries = parents.filter_map(|name| self.lookup(&name));
        // Note: it would be nice to avoid allocation here, but we need to reverse the
        // iterator later, so returning `impl Iterator` is not an option. We can only reverse
        // `DoubleEndedIterator`.
        database_entries.map(BreadcrumbEntry::from).collect()
    }
}
