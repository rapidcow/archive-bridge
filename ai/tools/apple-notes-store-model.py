#!/usr/bin/env python3
"""Decode the Core Data model that a NoteStore.sqlite carries in itself.

Core Data stores a copy of the app's object model in Z_MODELCACHE (raw
DEFLATE of an NSKeyedArchiver binary plist) and the model's fingerprint in
Z_METADATA (binary plist). This tool reads both and derives, from the model
alone, which SQL column holds each entity property. See
ai/notes/apple/store/schema-and-model.md for the format and the naming rule.

Python rather than Perl (as the rest of ai/tools) only because the standard
library has a binary-plist reader (plistlib) and Perl's core does not.

The database is opened with ?immutable=1, which never writes, but also
ignores any -wal. For a store whose -wal matters, first fold it in with
apple-notes-wal-merge.py (or VACUUM INTO a copy when the files are not
git-annex symlinks) and point this tool at the result.

Usage:
  apple-notes-store-model.py DB --meta      Z_METADATA: digest, framework version, ...
  apple-notes-store-model.py DB --model     entities, attributes, relationships
  apple-notes-store-model.py DB --columns   Z_ENT, entity, property, table.column (TSV)
  apple-notes-store-model.py DB --check     compare derived columns with pragma table_info
                                            (exit 1 on any mismatch)
  apple-notes-store-model.py DB --compare MOM
                                            compare the store's cached model with a
                                            compiled model, e.g. NotesShared.framework/
                                            Resources/NoteData.mom (exit 1 if they differ)
Several commands may be combined; with none, --meta --check.
"""

import argparse
import collections
import os
import plistlib
import sqlite3
import sys
import urllib.parse
import zlib

ATTRIBUTE_TYPES = {
	0: 'undefined', 100: 'int16', 200: 'int32', 300: 'int64', 400: 'decimal',
	500: 'double', 600: 'float', 700: 'string', 800: 'bool', 900: 'date',
	1000: 'binary', 1100: 'uuid', 1200: 'uri', 1800: 'transformable',
	2000: 'objectID', 2100: 'composite',
}
DELETE_RULES = {0: 'noAction', 1: 'nullify', 2: 'cascade', 3: 'deny'}
HISTORY_TABLES = {'ACHANGE', 'ATRANSACTION', 'ATRANSACTIONSTRING'}


def connect(path):
	uri = 'file:' + urllib.parse.quote(os.path.realpath(path)) + '?immutable=1'
	return sqlite3.connect(uri, uri=True)


class KeyedArchive:
	"""Just enough of NSKeyedUnarchiver: UID dereferencing and collections."""

	def __init__(self, data):
		plist = plistlib.loads(data)
		if plist.get('$archiver') != 'NSKeyedArchiver':
			raise ValueError('not an NSKeyedArchiver archive')
		self.objects = plist['$objects']
		self.root = self.ref(plist['$top']['root'])

	def ref(self, v):
		return self.objects[v.data] if isinstance(v, plistlib.UID) else v

	def classname(self, o):
		if isinstance(o, dict) and '$class' in o:
			return self.ref(o['$class'])['$classname']
		return None

	def value(self, v):
		o = self.ref(v)
		if o == '$null':
			return None
		c = self.classname(o)
		if c in ('NSArray', 'NSMutableArray', 'NSSet', 'NSMutableSet'):
			return [self.value(x) for x in o['NS.objects']]
		if c in ('NSDictionary', 'NSMutableDictionary'):
			return {self.value(k): self.value(x) for k, x in zip(o['NS.keys'], o['NS.objects'])}
		if c == 'NSKnownKeysDictionary1':
			# Keys live in a shared mapping strategy; the values are enough here.
			return [self.ref(x) for x in self.ref(o['values'])['NS.objects']]
		return o


Attribute = collections.namedtuple('Attribute', 'name type transformer value_class optional')
Relationship = collections.namedtuple('Relationship', 'name destination inverse min_count max_count delete_rule optional')
Entity = collections.namedtuple('Entity', 'name superentity abstract class_name properties')


def read_model(con):
	"""Returns {entity name: Entity} from the store's Z_MODELCACHE."""
	blob = con.execute('SELECT Z_CONTENT FROM Z_MODELCACHE').fetchone()[0]
	return parse_model(zlib.decompress(blob, -15))


def parse_model(data):
	"""Returns {entity name: Entity} from an archived NSManagedObjectModel
	(Z_MODELCACHE after inflating, or a compiled .mom file as is).
	Properties include inherited ones, as archived."""
	ar = KeyedArchive(data)
	def members(v):
		v = ar.value(v)
		return list(v.values()) if isinstance(v, dict) else v
	entities = {}
	for e in members(ar.root['NSEntities']):
		sup = ar.ref(e['NSSuperentity']) if 'NSSuperentity' in e else None
		props = []
		for p in members(e['NSProperties']):
			p = ar.ref(p)
			name = ar.value(p['NSPropertyName'])
			optional = bool(ar.value(p.get('NSIsOptional')))
			if ar.classname(p) == 'NSAttributeDescription':
				t = ar.value(p['NSAttributeType'])
				props.append(Attribute(name, ATTRIBUTE_TYPES.get(t, t), ar.value(p.get('NSValueTransformerName')),
						ar.value(p.get('NSAttributeValueClassName')), optional))
			else:
				props.append(Relationship(name, ar.value(p.get('_NSDestinationEntityName')),
						ar.value(p.get('_NSInverseRelationshipName')), ar.value(p.get('NSMinCount')),
						ar.value(p.get('NSMaxCount')), DELETE_RULES.get(ar.value(p.get('NSDeleteRule'))), optional))
		name = ar.value(e['NSEntityName'])
		entities[name] = Entity(name, ar.value(sup['NSEntityName']) if isinstance(sup, dict) else None,
				bool(ar.value(e.get('NSIsAbstract'))), ar.value(e.get('NSClassNameForEntity')), props)
	return entities


def derive_columns(entities, entity_numbers):
	"""Returns [(Z_ENT, entity, property, table, column)] by Core Data's naming rule.

	Walk entities in Z_ENT order; take the properties each declares itself;
	skip to-many relationships; the k-th declaration of a column name within
	a table gets suffix k (none for 0). A to-one into an entity that has
	subentities also gets Z<dest Z_ENT>_<NAME> for the target's Z_ENT.
	"""
	def root(n):
		while entities[n].superentity:
			n = entities[n].superentity
		return n
	has_sub = {e.superentity for e in entities.values() if e.superentity}
	seen = collections.Counter()
	out = []
	for n in sorted(entities, key=lambda n: entity_numbers[n]):
		e = entities[n]
		table = 'Z' + root(n).upper()
		inherited = {p.name for p in entities[e.superentity].properties} if e.superentity else set()
		for p in e.properties:
			if p.name in inherited:
				continue
			if isinstance(p, Relationship) and p.max_count != 1:
				continue
			base = 'Z' + p.name.upper()
			k = seen[table, base]
			seen[table, base] += 1
			out.append((entity_numbers[n], n, p.name, table, base + (str(k) if k else '')))
			if isinstance(p, Relationship) and p.destination in has_sub:
				out.append((entity_numbers[n], n, p.name + '#ent', table,
						'Z%d_%s' % (entity_numbers[p.destination], p.name.upper())))
	return out


def compare_models(a, b):
	"""Structural differences between two models, 'store' = a, 'mom' = b.
	Ignores class names, indexes, user info and the like (as Core Data's
	version hashes do)."""
	def declared(m, n):
		# A compiled .mom lists only an entity's own properties; the cache
		# also repeats inherited ones. Compare what each entity declares.
		sup = m[n].superentity
		inherited = {p.name for p in m[sup].properties} if sup else set()
		return {p.name: p for p in m[n].properties if p.name not in inherited}
	out = []
	for n in sorted(set(a) | set(b)):
		if n not in b:
			out.append('entity only in store: %s' % n)
			continue
		if n not in a:
			out.append('entity only in mom:   %s' % n)
			continue
		if (a[n].superentity, a[n].abstract) != (b[n].superentity, b[n].abstract):
			out.append('%s: superentity/abstract %s vs %s' % (n, (a[n].superentity, a[n].abstract), (b[n].superentity, b[n].abstract)))
		pa = declared(a, n)
		pb = declared(b, n)
		for p in sorted(set(pa) | set(pb)):
			if p not in pb:
				out.append('%s.%s only in store' % (n, p))
			elif p not in pa:
				out.append('%s.%s only in mom' % (n, p))
			elif pa[p] != pb[p]:
				out.append('%s.%s: %s vs %s' % (n, p, pa[p], pb[p]))
	return out


def main():
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument('db')
	for c in ('meta', 'model', 'columns', 'check'):
		ap.add_argument('--' + c, action='store_true')
	ap.add_argument('--compare', metavar='MOM')
	args = ap.parse_args()
	if not (args.meta or args.model or args.columns or args.check or args.compare):
		args.meta = args.check = True

	con = connect(args.db)
	status = 0
	if args.meta:
		md = plistlib.loads(con.execute('SELECT Z_PLIST FROM Z_METADATA').fetchone()[0])
		for k in sorted(md):
			v = md[k]
			print('%s\t%s' % (k, '{%d entity hashes}' % len(v) if isinstance(v, dict) else v))
	numbers = dict((n, z) for z, n in con.execute('SELECT Z_ENT, Z_NAME FROM Z_PRIMARYKEY'))
	entities = read_model(con)
	if args.model:
		for n in sorted(entities, key=lambda n: numbers[n]):
			e = entities[n]
			print('\n## %d %s : %s%s' % (numbers[n], n, e.superentity, ' (abstract)' if e.abstract else ''))
			for p in sorted(e.properties, key=lambda p: (isinstance(p, Relationship), p.name)):
				if isinstance(p, Attribute):
					print('   attr %-40s %s%s' % (p.name, p.type, ' via ' + p.transformer if p.transformer else ''))
				else:
					print('   rel  %-40s -> %s (inverse %s, %s, delete %s)' % (p.name, p.destination, p.inverse,
							'to-one' if p.max_count == 1 else 'to-many', p.delete_rule))
	if args.compare:
		with open(args.compare, 'rb') as f:
			other = parse_model(f.read())
		differences = compare_models(entities, other)
		for d in differences:
			print(d)
		print('compare: %s' % ('differ (%d)' % len(differences) if differences else 'same entities, properties, types and relationships'))
		status = 1 if differences else status
	columns = derive_columns(entities, numbers)
	if args.columns:
		for row in columns:
			print('%d\t%s\t%s\t%s.%s' % row)
	if args.check:
		predicted = collections.defaultdict(set)
		for _, _, _, t, c in columns:
			predicted[t].add(c)
		tables = [t for (t,) in con.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
				if not t.startswith('Z_') and t not in HISTORY_TABLES]
		for t in sorted(set(tables) | set(predicted)):
			actual = {r[1] for r in con.execute('PRAGMA table_info(%s)' % t)} - {'Z_PK', 'Z_ENT', 'Z_OPT'}
			missing, extra = sorted(predicted[t] - actual), sorted(actual - predicted[t])
			if missing or extra:
				status = 1
				print('MISMATCH %s: derived but absent %s; present but not derived %s' % (t, missing, extra))
		print('check: %s (%d columns derived)' % ('MISMATCH' if status else 'ok', len(columns)))
	return status


if __name__ == '__main__':
	sys.exit(main())
