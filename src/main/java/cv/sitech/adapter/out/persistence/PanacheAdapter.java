package cv.sitech.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.persistence.mapper.EntityMapper;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

public abstract class PanacheAdapter<D, E, ID> {
  protected final PanacheRepositoryBase<E, ID> panache;
  protected final EntityMapper<D, E> mapper;

  protected PanacheAdapter(PanacheRepositoryBase<E, ID> panache, EntityMapper<D, E> mapper) {
    this.panache = panache;
    this.mapper = mapper;
  }

  public D save(D domain) {
    E entity = panache.getEntityManager().merge(mapper.toEntity(domain));
    return mapper.toDomain(entity);
  }

  public Optional<D> findById(ID id) {
    return panache.findByIdOptional(id).map(mapper::toDomain);
  }

  public List<D> findAll() {
    return panache.listAll().stream().map(mapper::toDomain).toList();
  }

  public Boolean deleteById(ID id) {
    return panache.deleteById(id);
  }

  protected List<D> list(String query, Object... params) {
    return panache.list(query, params).stream().map(mapper::toDomain).toList();
  }

  protected Optional<D> first(String query, Object... params) {
    return panache.find(query, params).firstResultOptional().map(mapper::toDomain);
  }
}
